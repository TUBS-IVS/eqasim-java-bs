package org.eqasim.braunschweig.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eqasim.braunschweig.parking.ParkingCostCalculator.Result;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parking cost of one stay (design section 3.2). The 26 golden cases in {@code parking/parking_golden_cases.json} are
 * the cross-language contract: the Python reference braunschweig.parking.cost (eqasim-bs) evaluates the same file to
 * the same cents and outcomes. The fixture tariffs pin the arithmetic, not real tariffs. The file is a byte-for-byte
 * copy of the one scripts/export_parking_golden_cases.py writes (eqasim-bs tests/fixtures/parking); regenerate it
 * there and copy it, never edit it here.
 */
public class ParkingCostCalculatorTest {
	static final String GOLDEN_CASES_RESOURCE = "/parking/parking_golden_cases.json";

	/** G01..G26 of the plan's golden table; a truncated or replaced fixture must not pass by asserting fewer cases. */
	private static final int GOLDEN_CASE_COUNT = 26;

	/**
	 * The keys of one golden case, as the Python generator writes them (braunschweig.parking.golden_cases.CASE_FIELDS):
	 * a dropped or renamed key fails instead of being read as absent.
	 */
	private static final Set<String> CASE_FIELDS = Set.of("id", "zone_id", "arrival_s", "departure_s", "purpose",
			"parking_free", "resident_of_zone", "terminal", "expected_cents", "expected_outcome", "expected_error");

	private static final int SECONDS_PER_DAY = 86400;

	static JsonNode readResource(String name) throws Exception {
		URL resource = ParkingCostCalculatorTest.class.getResource(name);
		assertNotNull("test resource " + name + " is missing", resource);
		return new ObjectMapper().readTree(Path.of(resource.toURI()).toFile());
	}

	/** The golden file's tariffs block, parsed by the production zone parser (the code that reads the tariff model). */
	static Map<String, ZoneTariff> goldenTariffs() throws Exception {
		Map<String, ZoneTariff> tariffs = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : readResource(GOLDEN_CASES_RESOURCE).get("tariffs").properties()) {
			tariffs.put(entry.getKey(), ParkingTariffs.parseZone(entry.getKey(), entry.getValue()));
		}
		return tariffs;
	}

	private static ZoneTariff zone(String json) throws Exception {
		return ParkingTariffs.parseZone("test_zone", new ObjectMapper().readTree(json));
	}

	@Test
	public void reproducesEveryGoldenCase() throws Exception {
		JsonNode golden = readResource(GOLDEN_CASES_RESOURCE);
		assertEquals(1, golden.get("schema_version").intValue());
		Map<String, ZoneTariff> tariffs = goldenTariffs();
		JsonNode cases = golden.get("cases");
		assertEquals(GOLDEN_CASE_COUNT, cases.size());
		Set<String> ids = new HashSet<>();
		Set<ParkingOutcome> pinnedOutcomes = EnumSet.noneOf(ParkingOutcome.class);
		int errorCases = 0;
		List<String> failures = new ArrayList<>();
		for (JsonNode goldenCase : cases) {
			String id = goldenCase.get("id").asText();
			assertTrue("duplicate golden case id " + id, ids.add(id));
			assertEquals(id + ": keys of the golden case", CASE_FIELDS, fieldNames(goldenCase));
			ZoneTariff tariff = tariffs.get(goldenCase.get("zone_id").asText());
			assertNotNull(id + ": unknown zone " + goldenCase.get("zone_id"), tariff);
			double arrival_s = wholeSeconds(goldenCase, "arrival_s");
			double departure_s = departure(goldenCase, tariff);
			String purpose = goldenCase.get("purpose").asText();
			boolean parkingFree = literalBoolean(goldenCase, "parking_free");
			boolean residentOfZone = literalBoolean(goldenCase, "resident_of_zone");
			if (literalBoolean(goldenCase, "expected_error")) {
				// The generator writes no expectation for an invalid stay: both fields are JSON null.
				assertTrue(id + ": an error case carries expected_cents = null", goldenCase.get("expected_cents").isNull());
				assertTrue(id + ": an error case carries expected_outcome = null",
						goldenCase.get("expected_outcome").isNull());
				errorCases++;
				try {
					Result result = ParkingCostCalculator.cents(tariff, arrival_s, departure_s, purpose, parkingFree,
							residentOfZone);
					failures.add(id + ": expected IllegalArgumentException, got " + result);
				} catch (IllegalArgumentException expected) {
					// The golden contract: an invalid stay raises in both implementations.
				}
				continue;
			}
			JsonNode expectedCents = goldenCase.get("expected_cents");
			assertTrue(id + ": expected_cents must be an integral number", expectedCents.isIntegralNumber());
			Result expected = new Result(expectedCents.longValue(),
					ParkingOutcome.valueOf(goldenCase.get("expected_outcome").asText()));
			pinnedOutcomes.add(expected.outcome());
			Result result = ParkingCostCalculator.cents(tariff, arrival_s, departure_s, purpose, parkingFree, residentOfZone);
			if (!expected.equals(result)) {
				failures.add(id + ": expected " + expected + ", got " + result);
			}
		}
		assertTrue(String.join("; ", failures), failures.isEmpty());
		assertEquals(1, errorCases);
		// Every outcome the calculator can return is pinned; NO_ZONE is decided by the car cost model, never here.
		assertEquals(EnumSet.complementOf(EnumSet.of(ParkingOutcome.NO_ZONE)), pinnedOutcomes);
	}

	/** Golden times are integral JSON numbers; the calculator receives them as MATSim doubles. */
	private static double wholeSeconds(JsonNode goldenCase, String field) {
		JsonNode value = goldenCase.get(field);
		assertTrue(goldenCase.get("id") + ": " + field + " must be an integral number, got " + value,
				value != null && value.isIntegralNumber());
		return value.asDouble();
	}

	private static boolean literalBoolean(JsonNode goldenCase, String field) {
		JsonNode value = goldenCase.get(field);
		assertTrue(goldenCase.get("id") + ": " + field + " must be a JSON boolean, got " + value,
				value != null && value.isBoolean());
		return value.booleanValue();
	}

	/**
	 * A non-terminal case carries its departure. A terminal case carries departure_s = null, as the Python generator
	 * writes it: the implementation derives the departure from the arrival by the terminal rule (assumption T1). The
	 * rule's results for G07 and G08 are pinned in {@link #terminalDepartureEndsWithTheFeeWindowOfTheArrivalDay}.
	 */
	private static double departure(JsonNode goldenCase, ZoneTariff tariff) {
		if (!literalBoolean(goldenCase, "terminal")) {
			return wholeSeconds(goldenCase, "departure_s");
		}
		JsonNode recorded = goldenCase.get("departure_s");
		assertTrue(goldenCase.get("id") + ": a terminal case carries departure_s = null, got " + recorded,
				recorded != null && recorded.isNull());
		return ParkingCostCalculator.terminalDeparture_s(wholeSeconds(goldenCase, "arrival_s"), tariff.feeEnd_s());
	}

	private static Set<String> fieldNames(JsonNode node) {
		Set<String> names = new TreeSet<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	/** The closed form equals the day-by-day sum of design section 3.2 (transcribed below) on a grid of stays. */
	@Test
	public void chargeableSecondsEqualsTheDayByDaySumOfTheDesign() {
		int[][] windows = { { 32400, 72000 }, { 0, 86400 }, { 21600, 86400 }, { 36000, 64800 }, { 0, 1 }, { 86399, 86400 } };
		long[] times = { 0, 1, 3600, 32399, 32400, 32401, 50000, 71999, 72000, 72001, 86399, 86400, 86401, 118800, 150000,
				172799, 172800, 172801, 200000, 259200, 300000 };
		int checked = 0;
		for (int[] window : windows) {
			for (long arrival_s : times) {
				for (long departure_s : times) {
					if (departure_s < arrival_s) {
						continue;
					}
					assertEquals("stay [" + arrival_s + ", " + departure_s + "] window [" + window[0] + ", " + window[1] + "]",
							dayByDaySum(arrival_s, departure_s, window[0], window[1]),
							ParkingCostCalculator.chargeableSeconds(arrival_s, departure_s, window[0], window[1]));
					checked++;
				}
			}
		}
		assertEquals(6 * 21 * 22 / 2, checked);
	}

	/** Design section 3.2 verbatim: the sum over days k = floor(a / 86400) .. floor((d - 1) / 86400). */
	private static long dayByDaySum(long arrival_s, long departure_s, int feeStart_s, int feeEnd_s) {
		long sum = 0;
		for (long day = Math.floorDiv(arrival_s, SECONDS_PER_DAY); day <= Math.floorDiv(departure_s - 1,
				SECONDS_PER_DAY); day++) {
			long dayStart_s = day * SECONDS_PER_DAY;
			sum += Math.max(0, Math.min(departure_s, dayStart_s + feeEnd_s) - Math.max(arrival_s, dayStart_s + feeStart_s));
		}
		return sum;
	}

	@Test
	public void aWholeDayWindowChargesTheWholeStayAcrossDaysAndADisjointWindowNothing() {
		assertEquals(3 * SECONDS_PER_DAY - 100, ParkingCostCalculator.chargeableSeconds(50, 3 * SECONDS_PER_DAY - 50, 0, 86400));
		assertEquals(0, ParkingCostCalculator.chargeableSeconds(73800, 79200, 32400, 72000));
		assertEquals(0, ParkingCostCalculator.chargeableSeconds(40000, 40000, 0, 86400));
	}

	@Test
	public void terminalDepartureEndsWithTheFeeWindowOfTheArrivalDay() {
		// The car pays until the fee window of its arrival day ends (G07).
		assertEquals(72000.0, ParkingCostCalculator.terminalDeparture_s(70200, 72000), 0.0);
		// Arriving after the window: the departure is the arrival itself, the stay is free (G08).
		assertEquals(75600.0, ParkingCostCalculator.terminalDeparture_s(75600, 72000), 0.0);
		assertEquals(SECONDS_PER_DAY + 72000.0, ParkingCostCalculator.terminalDeparture_s(100800, 72000), 0.0);
		assertEquals(72000.0, ParkingCostCalculator.terminalDeparture_s(70200.7, 72000), 0.0);
		// Never before the (unfloored) arrival, so the stay stays valid for cents(...).
		assertEquals(75600.5, ParkingCostCalculator.terminalDeparture_s(75600.5, 72000), 0.0);
		assertThrows(IllegalArgumentException.class, () -> ParkingCostCalculator.terminalDeparture_s(0, 0));
		assertThrows(IllegalArgumentException.class, () -> ParkingCostCalculator.terminalDeparture_s(0, 86401));
		assertThrows(IllegalArgumentException.class, () -> ParkingCostCalculator.terminalDeparture_s(Double.NaN, 72000));
	}

	/** MATSim times are doubles; the sub-second part is floored away before minutes and billing units are counted. */
	@Test
	public void fractionalSecondsAreFlooredBeforeTheArithmetic() throws Exception {
		ZoneTariff zoneIa = goldenTariffs().get("fx_bs_ia");
		// 1800.5 s unfloored would start a 31st minute (93 ct); floored it is exactly 30 minutes (90 ct).
		assertEquals(new Result(90, ParkingOutcome.PAID_METERED),
				ParkingCostCalculator.cents(zoneIa, 36000.0, 37800.5, "shop", false, false));
		assertEquals(new Result(93, ParkingOutcome.PAID_METERED),
				ParkingCostCalculator.cents(zoneIa, 36000.9, 37860.4, "shop", false, false));
		assertEquals(1800, ParkingCostCalculator.chargeableSeconds(36000.9, 37800.9, 32400, 72000));
	}

	@Test
	public void invalidStaysAndWindowsAreRejectedBeforeAnyRule() throws Exception {
		ZoneTariff zoneIa = goldenTariffs().get("fx_bs_ia");
		// The home rule would return 0 ct; a departure before the arrival is still an input error.
		assertThrows(IllegalArgumentException.class, () -> ParkingCostCalculator.cents(zoneIa, 37860, 36000, "home", false,
				false));
		assertThrows(IllegalArgumentException.class, () -> ParkingCostCalculator.chargeableSeconds(100.7, 100.2, 0, 86400));
		for (double invalid : new double[] { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0 }) {
			assertThrows(IllegalArgumentException.class,
					() -> ParkingCostCalculator.cents(zoneIa, invalid, 40000, "shop", false, false));
			assertThrows(IllegalArgumentException.class,
					() -> ParkingCostCalculator.cents(zoneIa, 30000, invalid, "shop", false, false));
		}
		assertThrows(IllegalArgumentException.class, () -> ParkingCostCalculator.chargeableSeconds(0, 10, 72000, 32400));
		assertThrows(IllegalArgumentException.class, () -> ParkingCostCalculator.chargeableSeconds(0, 10, -1, 100));
		assertThrows(IllegalArgumentException.class, () -> ParkingCostCalculator.chargeableSeconds(0, 10, 0, 86401));
		assertThrows(NullPointerException.class, () -> ParkingCostCalculator.cents(zoneIa, 0, 10, null, false, false));
		assertThrows(NullPointerException.class, () -> ParkingCostCalculator.cents(null, 0, 10, "shop", false, false));
	}

	/**
	 * Price = (billed minutes * hourly rate + 30) / 60, i.e. rounded half up to the cent. Every golden case has a price
	 * without remainder, so the rounding is pinned here, by hand from the section 3.2 formula.
	 */
	@Test
	public void meteredPricesRoundHalfUpToTheCent() throws Exception {
		// One started minute: 90 ct/h = 1.5 ct -> 2; 150 ct/h = 2.5 ct -> 3; 80 ct/h = 1.33 ct -> 1.
		assertEquals(new Result(2, ParkingOutcome.PAID_METERED), minuteStay(90, 60));
		assertEquals(new Result(3, ParkingOutcome.PAID_METERED), minuteStay(150, 60));
		assertEquals(new Result(1, ParkingOutcome.PAID_METERED), minuteStay(80, 60));
		// Seven minutes at 100 ct/h = 11.67 ct -> 12.
		assertEquals(new Result(12, ParkingOutcome.PAID_METERED), minuteStay(100, 420));
		// One minute at 20 ct/h = 0.33 ct -> 0: a chargeable stay that costs nothing is FREE_WITHIN_LIMIT.
		assertEquals(new Result(0, ParkingOutcome.FREE_WITHIN_LIMIT), minuteStay(20, 60));
	}

	/** A shop stay of {@code duration_s} from 10:00 in a street zone billed per started minute at {@code hourlyRateCents}. */
	private static Result minuteStay(long hourlyRateCents, int duration_s) throws Exception {
		ZoneTariff perMinute = zone("""
				{"zone_type": "street_paid", "hourly_rate_cents": %d, "billing_unit_min": 1,
				 "free_if_stay_at_most_min": null, "first_period_min": null, "first_period_cents": null,
				 "daily_cap_cents": null, "max_stay_min": null, "long_stay_product_cents": null, "member_day_cents": null,
				 "guest_day_cents": null, "fee_start_s": 32400, "fee_end_s": 72000, "resident_exempt": false}
				""".formatted(hourlyRateCents));
		return ParkingCostCalculator.cents(perMinute, 36000, 36000 + duration_s, "shop", false, false);
	}

	/**
	 * Chargeable minutes are ceil(chargeable_s / 60) and billing units are started units: one second beyond a limit
	 * counts. Every golden stay is a whole number of minutes, so these boundaries are pinned here.
	 */
	@Test
	public void oneSecondBeyondALimitIsAStartedMinuteOrUnit() throws Exception {
		Map<String, ZoneTariff> tariffs = goldenTariffs();
		ZoneTariff salzgitter = tariffs.get("fx_sz"); // free up to 30 min, first period 60 min for 70 ct
		assertEquals(new Result(0, ParkingOutcome.FREE_WITHIN_LIMIT),
				ParkingCostCalculator.cents(salzgitter, 39600, 39600 + 1800, "shop", false, false));
		assertEquals(new Result(70, ParkingOutcome.PAID_METERED),
				ParkingCostCalculator.cents(salzgitter, 39600, 39600 + 1801, "shop", false, false));
		ZoneTariff zoneIa = tariffs.get("fx_bs_ia"); // 180 ct/h per minute, maximum stay 180 min, long-stay product 900 ct
		assertEquals(new Result(540, ParkingOutcome.PAID_METERED),
				ParkingCostCalculator.cents(zoneIa, 36000, 36000 + 10800, "shop", false, false));
		assertEquals(new Result(900, ParkingOutcome.PAID_LONG_STAY),
				ParkingCostCalculator.cents(zoneIa, 36000, 36000 + 10801, "shop", false, false));
		ZoneTariff peine = tariffs.get("fx_pe"); // 100 ct/h in 30-minute units
		assertEquals(new Result(50, ParkingOutcome.PAID_METERED),
				ParkingCostCalculator.cents(peine, 36000, 36000 + 1800, "shop", false, false));
		assertEquals(new Result(100, ParkingOutcome.PAID_METERED),
				ParkingCostCalculator.cents(peine, 36000, 36000 + 1801, "shop", false, false));
	}

	/** Integer money never wraps around: an absurd rate fails loudly instead of producing a small or negative price. */
	@Test
	public void moneyArithmeticFailsLoudlyInsteadOfOverflowing() throws Exception {
		ZoneTariff absurd = zone("""
				{"zone_type": "street_paid", "hourly_rate_cents": 4611686018427387903, "billing_unit_min": 1,
				 "free_if_stay_at_most_min": null, "first_period_min": null, "first_period_cents": null,
				 "daily_cap_cents": null, "max_stay_min": null, "long_stay_product_cents": null, "member_day_cents": null,
				 "guest_day_cents": null, "fee_start_s": 32400, "fee_end_s": 72000, "resident_exempt": false}
				""");
		assertThrows(ArithmeticException.class, () -> ParkingCostCalculator.cents(absurd, 32400, 72000, "shop", false, false));
	}

	@Test
	public void resultRejectsNegativeCentsAndAMissingOutcome() {
		assertThrows(IllegalArgumentException.class, () -> new Result(-1, ParkingOutcome.PAID_METERED));
		assertThrows(NullPointerException.class, () -> new Result(0, null));
	}
}
