package org.eqasim.braunschweig.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.TreeMap;
import java.util.TreeSet;

import org.eqasim.braunschweig.parking.ParkingCostCalculator.Result;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parking cost of one stay (design section 3.2, the v2 lever 2 and the v3 maximum-stay feasibility and EV exemption). The
 * golden cases in {@code parking/parking_golden_cases.json} are the cross-language contract: the Python reference
 * braunschweig.parking.cost (eqasim-bs) evaluates the same file to the same cents and outcomes. The fixture tariffs pin
 * the arithmetic, not real tariffs. The file is a byte-for-byte copy of the one scripts/export_parking_golden_cases.py
 * writes (eqasim-bs tests/fixtures/parking); regenerate it there and copy it, never edit it here. Every case is
 * evaluated as the Python reference evaluates it (braunschweig.parking.golden_cases.evaluate_case): the terminal-stay
 * rule T1 for a terminal case, then the minimum parked duration L1 of the case, then the pricing with the resident
 * district flag and the EV exemption flag OF THE CASE (rule R2, spec v3 A5) and the tariff of the file (its
 * resident_permits_valid and ev_free_minutes included). The cases that reach garage options (a destination) belong to
 * GarageParkingCostCalculatorTest.
 */
public class ParkingCostCalculatorTest {
	static final String GOLDEN_CASES_RESOURCE = "/parking/parking_golden_cases.json";

	/** The schema-1 tariff model of version 1, kept as a resource of its own: schema 2 is additive. */
	static final String SCHEMA_1_TARIFFS_RESOURCE = "/parking/parking_tariffs_fixture_schema1.json";

	/**
	 * Version of the golden file: 3 since every case carries minimum_stay_min and resident_of_district, 4 since the garage
	 * options (spec Amendment E: destination, decay and expected garage probability per case, the garages and the option
	 * cases), 5 since the separate commuter decay length (spec Amendment H: garage_decay_commute_m per case), 6 since the v3
	 * mechanics (spec v3: ev_exempt and option_price_beta_u_per_eur per case, max_stay_min per garage, ev_free_minutes per
	 * zone, the families F, P and X and the option cases S).
	 */
	private static final int GOLDEN_SCHEMA_VERSION = 6;

	/**
	 * G01..G38 (the pricing before the minimum parked duration), L01..L08 at L = 15 and their twins L01Z..L08Z at L = 0,
	 * V01..V23 (schema 2: the cheapest of the street, garage and commuter product), R01..R13 (rule R2), E01..E38 (garage
	 * options), F01..F08 (maximum-stay feasibility), P01..P06 (price in the option weights) and X01..X09 (EV exemption); the
	 * cases with a destination are priced by GarageParkingCostCalculatorTest, not here; a truncated or replaced fixture must
	 * not pass by asserting fewer cases.
	 */
	private static final Map<String, Integer> CASES_PER_FAMILY = Map.of("G", 38, "L", 8, "LZ", 8, "V", 23, "R", 13, "E", 38,
			"F", 8, "P", 6, "X", 9);

	/**
	 * The keys of one golden case, as the Python generator writes them (braunschweig.parking.golden_cases.CASE_FIELDS):
	 * a dropped or renamed key fails instead of being read as absent.
	 */
	private static final Set<String> CASE_FIELDS = Set.of("id", "zone_id", "arrival_s", "departure_s", "purpose",
			"parking_free", "resident_of_zone", "resident_of_district", "ev_exempt", "terminal", "minimum_stay_min",
			"destination_x_m", "destination_y_m", "garage_decay_m", "garage_decay_commute_m", "option_price_beta_u_per_eur",
			"expected_cents", "expected_outcome", "expected_garage_probability", "expected_error");

	private static final int SECONDS_PER_DAY = 86400;

	static JsonNode readResource(String name) throws Exception {
		URL resource = ParkingCostCalculatorTest.class.getResource(name);
		assertNotNull("test resource " + name + " is missing", resource);
		return new ObjectMapper().readTree(Path.of(resource.toURI()).toFile());
	}

	/** The golden file's tariffs block (zone entries of tariff schema 2), parsed by the production zone parser. */
	static Map<String, ZoneTariff> goldenTariffs() throws Exception {
		Map<String, ZoneTariff> tariffs = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : readResource(GOLDEN_CASES_RESOURCE).get("tariffs").properties()) {
			tariffs.put(entry.getKey(), ParkingTariffs.parseZone(entry.getKey(), entry.getValue(), ParkingTariffs.SCHEMA_VERSION));
		}
		return tariffs;
	}

	/** A zone of a tariff model of schema 1 (the 14 keys of version 1), parsed by the production zone parser. */
	private static ZoneTariff zone(String json) throws Exception {
		return ParkingTariffs.parseZone("test_zone", new ObjectMapper().readTree(json), 1);
	}

	/** Family of a golden case id: G for G01, L for L01, LZ for L01Z, V for V01 and R for R01. */
	private static String family(String id) {
		return id.endsWith("Z") ? id.substring(0, 1) + "Z" : id.substring(0, 1);
	}

	@Test
	public void reproducesEveryGoldenCase() throws Exception {
		JsonNode golden = readResource(GOLDEN_CASES_RESOURCE);
		assertEquals(GOLDEN_SCHEMA_VERSION, golden.get("schema_version").intValue());
		Map<String, ZoneTariff> tariffs = goldenTariffs();
		JsonNode cases = golden.get("cases");
		assertEquals(CASES_PER_FAMILY.values().stream().mapToInt(Integer::intValue).sum(), cases.size());
		Set<String> ids = new HashSet<>();
		Map<String, Integer> casesPerFamily = new TreeMap<>();
		Set<ParkingOutcome> pinnedOutcomes = EnumSet.noneOf(ParkingOutcome.class);
		int errorCases = 0;
		int priced = 0;
		List<String> failures = new ArrayList<>();
		for (JsonNode goldenCase : cases) {
			String id = goldenCase.get("id").asText();
			assertTrue("duplicate golden case id " + id, ids.add(id));
			casesPerFamily.merge(family(id), 1, Integer::sum);
			assertEquals(id + ": keys of the golden case", CASE_FIELDS, fieldNames(goldenCase));
			if (!goldenCase.get("destination_x_m").isNull()) {
				// The cases with a destination need the garages and the distance mixture: GarageParkingCostCalculatorTest.
				continue;
			}
			priced++;
			ZoneTariff tariff = tariffs.get(goldenCase.get("zone_id").asText());
			assertNotNull(id + ": unknown zone " + goldenCase.get("zone_id"), tariff);
			if (literalBoolean(goldenCase, "expected_error")) {
				// The generator writes no expectation for an invalid stay: both fields are JSON null.
				assertTrue(id + ": an error case carries expected_cents = null", goldenCase.get("expected_cents").isNull());
				assertTrue(id + ": an error case carries expected_outcome = null",
						goldenCase.get("expected_outcome").isNull());
				errorCases++;
				try {
					Result result = evaluate(goldenCase, tariff);
					failures.add(id + ": expected IllegalArgumentException, got " + result);
				} catch (IllegalArgumentException expected) {
					// The golden contract: an invalid stay raises in both implementations, with the stay check's message.
					assertTrue(id + ": " + expected.getMessage(), expected.getMessage().contains("before arrival_s"));
				}
				continue;
			}
			JsonNode expectedCents = goldenCase.get("expected_cents");
			assertTrue(id + ": expected_cents must be an integral number", expectedCents.isIntegralNumber());
			Result expected = new Result(expectedCents.longValue(),
					ParkingOutcome.valueOf(goldenCase.get("expected_outcome").asText()));
			pinnedOutcomes.add(expected.outcome());
			Result result = evaluate(goldenCase, tariff);
			if (!expected.equals(result)) {
				failures.add(id + ": expected " + expected + ", got " + result);
			}
		}
		assertTrue(String.join("; ", failures), failures.isEmpty());
		assertEquals(new TreeMap<>(CASES_PER_FAMILY), casesPerFamily);
		assertEquals(1, errorCases);
		// The primary path is taken: every case without a destination is priced here (the garage cases are the other
		// harness's), so a golden file that moved every case behind a destination would not pass unnoticed.
		assertEquals("cases without a destination: G 38, L 16, V 23, R 13, F 3 and X 7", 100, priced);
		// Every outcome the calculator can return is pinned, the two schema-2 products and the two schema-5 outcomes included;
		// NO_ZONE is decided by the car cost model, never here, and PAID_EXPECTED belongs to GarageParkingCostCalculator.
		assertEquals(EnumSet.complementOf(EnumSet.of(ParkingOutcome.NO_ZONE, ParkingOutcome.PAID_EXPECTED)), pinnedOutcomes);
	}

	/**
	 * Schema 2 is additive: a tariff model of schema 1 (the committed fixture of version 1) loads, and G01..G38,
	 * L01..L08 and L01Z..L08Z, the cases on its zones, keep their cents and outcomes. Rule R2 and the products of schema 2
	 * do not exist there: a schema-1 zone has no garage, no commuter product, and the permit flag of its zone type.
	 */
	@Test
	public void aSchemaOneModelPricesTheVersionOneCasesUnchanged() throws Exception {
		URL resource = ParkingCostCalculatorTest.class.getResource(SCHEMA_1_TARIFFS_RESOURCE);
		assertNotNull("test resource " + SCHEMA_1_TARIFFS_RESOURCE + " is missing", resource);
		ParkingTariffs schemaOne = ParkingTariffs.read(Path.of(resource.toURI()));
		assertEquals(1, schemaOne.schemaVersion());
		int priced = 0;
		List<String> failures = new ArrayList<>();
		for (JsonNode goldenCase : readResource(GOLDEN_CASES_RESOURCE).get("cases")) {
			String id = goldenCase.get("id").asText();
			if (!Set.of("G", "L", "LZ").contains(family(id))) {
				continue;
			}
			ZoneTariff tariff = schemaOne.zone(goldenCase.get("zone_id").asText()).orElseThrow();
			priced++;
			if (literalBoolean(goldenCase, "expected_error")) {
				assertThrows(id, IllegalArgumentException.class, () -> evaluate(goldenCase, tariff));
				continue;
			}
			Result expected = new Result(goldenCase.get("expected_cents").longValue(),
					ParkingOutcome.valueOf(goldenCase.get("expected_outcome").asText()));
			Result result = evaluate(goldenCase, tariff);
			if (!expected.equals(result)) {
				failures.add(id + ": expected " + expected + ", got " + result);
			}
		}
		assertTrue(String.join("; ", failures), failures.isEmpty());
		assertEquals(38 + 8 + 8, priced);
	}

	/**
	 * One golden case as braunschweig.parking.golden_cases.evaluate_case evaluates it: the departure of a terminal case
	 * from the terminal-stay rule T1 (the STREET fee window), then the minimum parked duration of the case L1 (spec
	 * amendment A3), then the pricing with the district flag of the case.
	 */
	private static Result evaluate(JsonNode goldenCase, ZoneTariff tariff) {
		double arrival_s = wholeSeconds(goldenCase, "arrival_s");
		double departure_s = departure(goldenCase, tariff);
		int minimumStay_s = 60 * goldenCase.get("minimum_stay_min").intValue();
		double pricedDeparture_s = ParkingCostCalculator.minimumStayDeparture_s(arrival_s, departure_s, minimumStay_s);
		return ParkingCostCalculator.cents(tariff, arrival_s, pricedDeparture_s, goldenCase.get("purpose").asText(),
				literalBoolean(goldenCase, "parking_free"), literalBoolean(goldenCase, "resident_of_zone"),
				literalBoolean(goldenCase, "resident_of_district"), literalBoolean(goldenCase, "ev_exempt"));
	}

	/**
	 * Spec v3 A5, ASSUMPTIONS E1 and E2, hand-derived on fx_ev_street_v3 (street 180 ct/h in 1-min units, 09:00-20:00, no
	 * maximum stay, ev_free_minutes 240): an exempt person parks free up to and including 240 chargeable minutes (EV_EXEMPT,
	 * before the street product), one second more and the stay pays the whole schedule, never a deduction (241 started
	 * minutes = 723 ct); a person without the flag pays from the first minute; the flag changes nothing in a zone without the
	 * rule (fx_ev_expired_v3, whose rule the export switched off, and fx_bs_ib), and a home stay stays HOME.
	 */
	@Test
	public void anElectricCarParksFreeUpToTheLimitAndPaysTheFullScheduleBeyondIt() throws Exception {
		Map<String, ZoneTariff> tariffs = goldenTariffs();
		ZoneTariff evStreet = tariffs.get("fx_ev_street_v3");
		assertEquals(new Result(0, ParkingOutcome.EV_EXEMPT),
				ParkingCostCalculator.cents(evStreet, 36000, 36000 + 14400, "shop", false, false, false, true));
		assertEquals(new Result(723, ParkingOutcome.PAID_METERED),
				ParkingCostCalculator.cents(evStreet, 36000, 36000 + 14401, "shop", false, false, false, true));
		assertEquals(new Result(540, ParkingOutcome.PAID_METERED),
				ParkingCostCalculator.cents(evStreet, 36000, 36000 + 10800, "shop", false, false, false, false));
		// A zone without the rule (the export left ev_free_minutes empty for an expired one) prices an exempt person as anybody.
		assertEquals(new Result(540, ParkingOutcome.PAID_METERED), ParkingCostCalculator.cents(
				tariffs.get("fx_ev_expired_v3"), 36000, 36000 + 10800, "shop", false, false, false, true));
		assertEquals(new Result(0, ParkingOutcome.HOME),
				ParkingCostCalculator.cents(evStreet, 36000, 36000 + 3600, "home", false, false, false, true));
	}

	/**
	 * Ruling R-A-10 (ASSUMPTION E2): the exemption applies within the maximum stay only. fx_ev_clip_v3 states ev_free_minutes
	 * 240 and a maximum stay of 150 minutes without a long-stay product: an exempt stay of 150 chargeable minutes is free, a
	 * stay of 151 to 240 minutes is NOT exempt (it exceeds the maximum) and, with nothing to sell above it, is priced as the
	 * street at its metered price with the outcome NO_FEASIBLE_OPTION (180 ct/h in 1-min units: 151 min = 453 ct).
	 */
	@Test
	public void theExemptionNeverExtendsAStayBeyondTheMaximumStay() throws Exception {
		ZoneTariff clipped = goldenTariffs().get("fx_ev_clip_v3");
		assertEquals(new Result(0, ParkingOutcome.EV_EXEMPT),
				ParkingCostCalculator.cents(clipped, 36000, 36000 + 9000, "shop", false, false, false, true));
		assertEquals(new Result(453, ParkingOutcome.NO_FEASIBLE_OPTION),
				ParkingCostCalculator.cents(clipped, 36000, 36000 + 9001, "shop", false, false, false, true));
		assertEquals(new Result(453, ParkingOutcome.NO_FEASIBLE_OPTION),
				ParkingCostCalculator.cents(clipped, 36000, 36000 + 9060, "shop", false, false, false, false));
	}

	/**
	 * Spec v3 A1, ruling R-A-6 (ASSUMPTION M3), hand-derived on fx_ia_nolong_v3 (street 180 ct/h in 1-min units, 09:00-20:00,
	 * maximum stay 180 min, NO long-stay product): within the maximum the street prices (180 chargeable min = 540 ct); one
	 * second above it the street is no option and nothing else is for sale, so the stay is priced as the street at its metered
	 * price, the maximum ignored (181 started min = 543 ct), with the outcome NO_FEASIBLE_OPTION, and it is not an exception.
	 * A work stay is not helped by a zone commuter product that the zone does not have.
	 */
	@Test
	public void aStayAboveTheMaximumStayWithNothingToSellIsPricedAsTheStreetAndNamed() throws Exception {
		ZoneTariff noLongStay = goldenTariffs().get("fx_ia_nolong_v3");
		assertEquals(new Result(540, ParkingOutcome.PAID_METERED),
				ParkingCostCalculator.cents(noLongStay, 36000, 36000 + 10800, "shop", false, false, false));
		assertEquals(new Result(543, ParkingOutcome.NO_FEASIBLE_OPTION),
				ParkingCostCalculator.cents(noLongStay, 36000, 36000 + 10801, "work", false, false, false));
		// The early rules precede the feasibility test: employer-free parking and a home stay stay free.
		assertEquals(new Result(0, ParkingOutcome.EMPLOYER_FREE),
				ParkingCostCalculator.cents(noLongStay, 36000, 36000 + 10801, "work", true, false, false));
		assertEquals(new Result(0, ParkingOutcome.HOME),
				ParkingCostCalculator.cents(noLongStay, 36000, 36000 + 10801, "home", false, false, false));
		// The long-stay product of the zone with one keeps the stay feasible.
		assertEquals(new Result(900, ParkingOutcome.PAID_LONG_STAY),
				ParkingCostCalculator.cents(goldenTariffs().get("fx_bs_ia"), 36000, 36000 + 10801, "shop", false, false, false));
	}

	/**
	 * A zone commuter product keeps the street side of a work or education stay above the maximum stay alive (P2): the stay
	 * buys the product (PAID_COMMUTER, 300 ct here) while a shopping stay of the same length has nothing to sell above the
	 * maximum and is NO_FEASIBLE_OPTION at the metered price (181 started min at 180 ct/h = 543 ct).
	 */
	@Test
	public void aCommuterProductKeepsAWorkStayAboveTheMaximumStayPriced() throws Exception {
		ZoneTariff withCommuter = ParkingTariffs.parseZone("zone_commuter", new ObjectMapper().readTree("""
				{"zone_type": "street_paid", "hourly_rate_cents": 180, "billing_unit_min": 1, "free_if_stay_at_most_min": null,
				 "first_period_min": null, "first_period_cents": null, "daily_cap_cents": null, "max_stay_min": 180,
				 "long_stay_product_cents": null, "member_day_cents": null, "guest_day_cents": null, "fee_start_s": 32400,
				 "fee_end_s": 72000, "resident_exempt": false, "garage_hourly_rate_cents": null, "garage_billing_unit_min": null,
				 "garage_first_period_min": null, "garage_first_period_cents": null, "garage_daily_cap_cents": null,
				 "garage_fee_start_s": null, "garage_fee_end_s": null, "commuter_day_cents": 300, "search_time_min": null,
				 "resident_permits_valid": true, "ev_free_minutes": null}
				"""), 5);
		assertEquals(new Result(300, ParkingOutcome.PAID_COMMUTER),
				ParkingCostCalculator.cents(withCommuter, 36000, 36000 + 10801, "work", false, false, false));
		assertEquals(new Result(543, ParkingOutcome.NO_FEASIBLE_OPTION),
				ParkingCostCalculator.cents(withCommuter, 36000, 36000 + 10801, "shop", false, false, false));
		assertTrue(ParkingCostCalculator.streetSideFeasible(withCommuter, 10801, "work", false));
		assertFalse(ParkingCostCalculator.streetSideFeasible(withCommuter, 10801, "shop", false));
	}

	/**
	 * The street side of a stay (spec v3 A1): feasible within the maximum, below the free threshold, with a long-stay product or
	 * the commuter product of a commuter purpose; not feasible above the maximum without any of them. A resident zone keeps the
	 * v2 rule: a maximum stay without a long-stay product or a garage fails when the zone is constructed.
	 */
	@Test
	public void theStreetSideIsFeasibleExactlyWhenAProductCanServeTheStay() throws Exception {
		ZoneTariff noLongStay = goldenTariffs().get("fx_ia_nolong_v3");
		assertTrue(ParkingCostCalculator.streetSideFeasible(noLongStay, 10800, "shop", false));
		assertFalse(ParkingCostCalculator.streetSideFeasible(noLongStay, 10801, "shop", false));
		assertFalse(ParkingCostCalculator.streetSideFeasible(noLongStay, 10801, "work", false));
		assertTrue(ParkingCostCalculator.streetSideFeasible(goldenTariffs().get("fx_bs_ia"), 10801, "shop", false));
		assertTrue(ParkingCostCalculator.streetSideFeasible(goldenTariffs().get("fx_bs_ib_v2"), 10801, "work", false));
		ZoneTariff clipped = goldenTariffs().get("fx_ev_clip_v3");
		assertFalse(ParkingCostCalculator.streetSideFeasible(clipped, 9060, "shop", false));
		assertTrue(ParkingCostCalculator.streetSideFeasible(clipped, 9000, "shop", true));
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ParkingTariffs.parseZone("res",
				new ObjectMapper().readTree("""
						{"zone_type": "resident_zone", "hourly_rate_cents": 0, "billing_unit_min": 60,
						 "free_if_stay_at_most_min": null,
						 "first_period_min": null, "first_period_cents": null, "daily_cap_cents": null, "max_stay_min": 120,
						 "long_stay_product_cents": null, "member_day_cents": null, "guest_day_cents": null, "fee_start_s": 0,
						 "fee_end_s": 86400, "resident_exempt": true, "garage_hourly_rate_cents": null,
						 "garage_billing_unit_min": null, "garage_first_period_min": null, "garage_first_period_cents": null,
						 "garage_daily_cap_cents": null, "garage_fee_start_s": null, "garage_fee_end_s": null,
						 "commuter_day_cents": null, "search_time_min": null, "resident_permits_valid": true,
						 "ev_free_minutes": null}
						"""), 5));
		assertTrue(error.getMessage(), error.getMessage().contains("neither long_stay_product_cents nor the garage"));
	}

	/** Golden times are integral JSON numbers; the calculator receives them as MATSim doubles. */
	private static double wholeSeconds(JsonNode goldenCase, String field) {
		JsonNode value = goldenCase.get(field);
		assertTrue(goldenCase.get("id") + ": " + field + " must be an integral number, got " + value,
				value != null && value.isIntegralNumber());
		return value.asDouble();
	}

	static boolean literalBoolean(JsonNode goldenCase, String field) {
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
				ParkingCostCalculator.cents(zoneIa, 36000.0, 37800.5, "shop", false, false, false));
		assertEquals(new Result(93, ParkingOutcome.PAID_METERED),
				ParkingCostCalculator.cents(zoneIa, 36000.9, 37860.4, "shop", false, false, false));
		assertEquals(1800, ParkingCostCalculator.chargeableSeconds(36000.9, 37800.9, 32400, 72000));
	}

	/**
	 * The garage product prices the same floored interval as the street. Stay [36000, 39600.5) in fx_wob_v2: floored it is
	 * exactly one hour, so the street takes its first period of 110 ct and the garage one started hour of 100 ct, and the
	 * garage wins; unfloored, the half second would start a second hour (garage 200 ct, street 230 ct).
	 */
	@Test
	public void fractionalSecondsAreFlooredForTheGarageProductToo() throws Exception {
		ZoneTariff zoneWob = goldenTariffs().get("fx_wob_v2");
		assertEquals(new Result(100, ParkingOutcome.PAID_GARAGE),
				ParkingCostCalculator.cents(zoneWob, 36000.0, 39600.5, "shop", false, false, false));
		assertEquals(new Result(200, ParkingOutcome.PAID_GARAGE),
				ParkingCostCalculator.cents(zoneWob, 36000.0, 39601.0, "shop", false, false, false));
	}

	@Test
	public void invalidStaysAndWindowsAreRejectedBeforeAnyRule() throws Exception {
		ZoneTariff zoneIa = goldenTariffs().get("fx_bs_ia");
		// The home rule would return 0 ct; a departure before the arrival is still an input error.
		assertThrows(IllegalArgumentException.class,
				() -> ParkingCostCalculator.cents(zoneIa, 37860, 36000, "home", false, false, false));
		// Rule R2 would free the stay; a departure before the arrival is still an input error.
		assertThrows(IllegalArgumentException.class,
				() -> ParkingCostCalculator.cents(zoneIa, 37860, 36000, "shop", false, false, true));
		assertThrows(IllegalArgumentException.class, () -> ParkingCostCalculator.chargeableSeconds(100.7, 100.2, 0, 86400));
		for (double invalid : new double[] { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0 }) {
			assertThrows(IllegalArgumentException.class,
					() -> ParkingCostCalculator.cents(zoneIa, invalid, 40000, "shop", false, false, false));
			assertThrows(IllegalArgumentException.class,
					() -> ParkingCostCalculator.cents(zoneIa, 30000, invalid, "shop", false, false, false));
		}
		assertThrows(IllegalArgumentException.class, () -> ParkingCostCalculator.chargeableSeconds(0, 10, 72000, 32400));
		assertThrows(IllegalArgumentException.class, () -> ParkingCostCalculator.chargeableSeconds(0, 10, -1, 100));
		assertThrows(IllegalArgumentException.class, () -> ParkingCostCalculator.chargeableSeconds(0, 10, 0, 86401));
		assertThrows(NullPointerException.class, () -> ParkingCostCalculator.cents(zoneIa, 0, 10, null, false, false, false));
		assertThrows(NullPointerException.class, () -> ParkingCostCalculator.cents(null, 0, 10, "shop", false, false, false));
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
		return ParkingCostCalculator.cents(perMinute, 36000, 36000 + duration_s, "shop", false, false, false);
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
				ParkingCostCalculator.cents(salzgitter, 39600, 39600 + 1800, "shop", false, false, false));
		assertEquals(new Result(70, ParkingOutcome.PAID_METERED),
				ParkingCostCalculator.cents(salzgitter, 39600, 39600 + 1801, "shop", false, false, false));
		ZoneTariff zoneIa = tariffs.get("fx_bs_ia"); // 180 ct/h per minute, maximum stay 180 min, long-stay product 900 ct
		assertEquals(new Result(540, ParkingOutcome.PAID_METERED),
				ParkingCostCalculator.cents(zoneIa, 36000, 36000 + 10800, "shop", false, false, false));
		assertEquals(new Result(900, ParkingOutcome.PAID_LONG_STAY),
				ParkingCostCalculator.cents(zoneIa, 36000, 36000 + 10801, "shop", false, false, false));
		ZoneTariff peine = tariffs.get("fx_pe"); // 100 ct/h in 30-minute units
		assertEquals(new Result(50, ParkingOutcome.PAID_METERED),
				ParkingCostCalculator.cents(peine, 36000, 36000 + 1800, "shop", false, false, false));
		assertEquals(new Result(100, ParkingOutcome.PAID_METERED),
				ParkingCostCalculator.cents(peine, 36000, 36000 + 1801, "shop", false, false, false));
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
		assertThrows(ArithmeticException.class,
				() -> ParkingCostCalculator.cents(absurd, 32400, 72000, "shop", false, false, false));
	}

	/**
	 * The home purpose test of the fee rule H1, shared with the parking search time of lever 4 so that both agree on what
	 * home is: exactly the activity type home.
	 */
	@Test
	public void theHomePurposeTestIsExactlyTheTypeHome() {
		assertTrue(ParkingCostCalculator.isHomePurpose("home"));
		for (String other : new String[] { "work", "education", "shop", "Home", "home_1", "" }) {
			assertFalse(other, ParkingCostCalculator.isHomePurpose(other));
		}
		assertFalse(ParkingCostCalculator.isHomePurpose(null));
	}

	@Test
	public void resultRejectsNegativeCentsAndAMissingOutcome() {
		assertThrows(IllegalArgumentException.class, () -> new Result(-1, ParkingOutcome.PAID_METERED));
		assertThrows(NullPointerException.class, () -> new Result(0, null));
	}

	/**
	 * Assumption L1: the priced stay is [arrival, max(departure, arrival + minimum)), so a shorter stay is priced for the
	 * minimum and a longer one keeps its departure; a minimum of 0 keeps every departure (version 1).
	 */
	@Test
	public void minimumStayDepartureIsTheLaterOfTheDepartureAndTheArrivalPlusTheMinimum() {
		assertEquals(36900.0, ParkingCostCalculator.minimumStayDeparture_s(36000, 36000, 900), 0.0);
		assertEquals(36900.0, ParkingCostCalculator.minimumStayDeparture_s(36000, 36300, 900), 0.0);
		assertEquals(36900.0, ParkingCostCalculator.minimumStayDeparture_s(36000, 36900, 900), 0.0);
		assertEquals(39600.0, ParkingCostCalculator.minimumStayDeparture_s(36000, 39600, 900), 0.0);
		assertEquals(36000.0, ParkingCostCalculator.minimumStayDeparture_s(36000, 36000, 0), 0.0);
		assertEquals(36300.0, ParkingCostCalculator.minimumStayDeparture_s(36000, 36300, 0), 0.0);
		// MATSim times are not floored here; cents(...) floors them.
		assertEquals(36900.5, ParkingCostCalculator.minimumStayDeparture_s(36000.5, 36000.5, 900), 0.0);
		// Simulation seconds keep counting beyond midnight.
		assertEquals(SECONDS_PER_DAY + 500.0,
				ParkingCostCalculator.minimumStayDeparture_s(SECONDS_PER_DAY - 400, SECONDS_PER_DAY - 100, 900), 0.0);
	}

	/** A negative minimum fails, and so does an invalid stay, which the minimum would otherwise turn into a valid one. */
	@Test
	public void minimumStayDepartureRejectsANegativeMinimumAndAnInvalidStay() {
		assertThrows(IllegalArgumentException.class, () -> ParkingCostCalculator.minimumStayDeparture_s(36000, 36000, -1));
		// max(35000, 36000 + 900) would be a valid departure: the departure before the arrival must fail instead.
		assertThrows(IllegalArgumentException.class, () -> ParkingCostCalculator.minimumStayDeparture_s(36000, 35000, 900));
		for (double invalid : new double[] { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0 }) {
			assertThrows(IllegalArgumentException.class,
					() -> ParkingCostCalculator.minimumStayDeparture_s(invalid, 40000, 900));
			assertThrows(IllegalArgumentException.class,
					() -> ParkingCostCalculator.minimumStayDeparture_s(30000, invalid, 900));
		}
	}
}
