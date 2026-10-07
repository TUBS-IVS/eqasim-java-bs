package org.eqasim.braunschweig.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.eqasim.braunschweig.parking.GarageParkingCostCalculator.Result;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The garage options of a stay (spec Amendment E of parking cost zones v2, eqasim-bs issue #436) against the golden file
 * {@code parking/parking_golden_cases.json} of schema 4, a byte-for-byte copy of the file the Python reference writes
 * (eqasim-bs tests/fixtures/parking; the SHA-256 of the LF text is pinned below, so a changed copy fails here).
 * Every case of {@code cases} (the 90 cases of the earlier families G, L, LZ, V and R, and E01..E32: the expected cost over
 * the street and the garage options) is priced as braunschweig.parking.golden_cases.evaluate_case_detail prices it, to the
 * cent, with the same outcome and the garage probability within 1e-9; every case of {@code garage_option_cases} (O01..O69)
 * pins the price of one garage option under the three tariff structures. A failing test lists every mismatching case id.
 */
public class GarageParkingCostCalculatorTest {
	/** SHA-256 of the golden file with LF line endings, as the Python reference's blob. */
	private static final String GOLDEN_SHA256 = "62cb9bc3c6bae7401c8130f54dee1349afaf6f39ec6f0db7c8371777e2e9d14b";

	/** SHA-256 of the schema-3 tariff model fixture with LF line endings. */
	private static final String FIXTURE_SHA256 = "c7d30141a04ef6c1652c5451d7e3e478bba463a73668ed1cb3bb4994d4472718";

	private static final String FIXTURE_RESOURCE = "/parking/parking_tariffs_fixture.json";

	private static final int GOLDEN_SCHEMA_VERSION = 4;
	private static final double PROBABILITY_TOLERANCE = 1e-9;

	/**
	 * A pinned expectation keeps this distance in cents from a half cent (the Python generator asserts it), so that the
	 * Math.exp of Java cannot flip the rounding; the port checks the same margin on its own unrounded expectation.
	 */
	private static final double ROUNDING_MARGIN_CENTS = 1e-6;

	private static final Map<String, Integer> CASES_PER_FAMILY = Map.of("G", 38, "L", 8, "LZ", 8, "V", 23, "R", 13, "E", 32);

	private static final Set<String> CASE_FIELDS = Set.of("id", "zone_id", "arrival_s", "departure_s", "purpose",
			"parking_free", "resident_of_zone", "resident_of_district", "terminal", "minimum_stay_min", "destination_x_m",
			"destination_y_m", "garage_decay_m", "expected_cents", "expected_outcome", "expected_garage_probability",
			"expected_error");

	private static final Set<String> OPTION_CASE_FIELDS = Set.of("id", "garage_id", "arrival_s", "departure_s", "purpose",
			"expected_cents");

	private static String lfSha256(String resource) throws Exception {
		try (InputStream stream = GarageParkingCostCalculatorTest.class.getResourceAsStream(resource)) {
			assertNotNull("test resource " + resource + " is missing", stream);
			byte[] bytes = stream.readAllBytes();
			// A checkout with core.autocrlf stores the file with CRLF; the blob of the Python reference has LF.
			byte[] lf = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).replace("\r", "")
					.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
			StringBuilder hex = new StringBuilder();
			for (byte value : MessageDigest.getInstance("SHA-256").digest(lf)) {
				hex.append(String.format("%02x", value));
			}
			return hex.toString();
		}
	}

	@Test
	public void theCopiedGoldenFilesAreByteForByteTheOnesOfThePythonReference() throws Exception {
		assertEquals(GOLDEN_SHA256, lfSha256(ParkingCostCalculatorTest.GOLDEN_CASES_RESOURCE));
		assertEquals(FIXTURE_SHA256, lfSha256(FIXTURE_RESOURCE));
	}

	@Test
	public void theGoldenGaragesAreTheGaragesOfTheSchemaThreeFixtureModel() throws Exception {
		JsonNode golden = ParkingCostCalculatorTest.readResource(ParkingCostCalculatorTest.GOLDEN_CASES_RESOURCE);
		JsonNode fixture = ParkingCostCalculatorTest.readResource(FIXTURE_RESOURCE);
		assertEquals(fixture.get("garages"), golden.get("garages"));
		assertEquals(fixture.get("garage_max_distance_m"), golden.get("garage_max_distance_m"));
		assertEquals(3, fixture.get("schema_version").intValue());
		assertEquals(19, golden.get("garages").size());
	}

	@Test
	public void reproducesEveryGoldenCaseToTheCent() throws Exception {
		JsonNode golden = ParkingCostCalculatorTest.readResource(ParkingCostCalculatorTest.GOLDEN_CASES_RESOURCE);
		assertEquals(GOLDEN_SCHEMA_VERSION, golden.get("schema_version").intValue());
		Map<String, ZoneTariff> tariffs = ParkingCostCalculatorTest.goldenTariffs();
		List<GarageTariff> garages = new ArrayList<>();
		for (JsonNode garage : golden.get("garages")) {
			garages.add(ParkingTariffs.parseGarage(garage));
		}
		double maximumDistanceMeters = golden.get("garage_max_distance_m").doubleValue();
		Map<Double, GarageOptionModel> modelByDecay = new HashMap<>();
		GarageOptionCounters counters = new GarageOptionCounters();
		JsonNode cases = golden.get("cases");
		assertEquals(CASES_PER_FAMILY.values().stream().mapToInt(Integer::intValue).sum(), cases.size());
		Map<String, Integer> casesPerFamily = new TreeMap<>();
		Set<String> ids = new HashSet<>();
		Set<ParkingOutcome> pinnedOutcomes = EnumSet.noneOf(ParkingOutcome.class);
		List<String> failures = new ArrayList<>();
		int expectedCases = 0;
		int errorCases = 0;
		for (JsonNode goldenCase : cases) {
			String id = goldenCase.get("id").asText();
			assertTrue("duplicate golden case id " + id, ids.add(id));
			casesPerFamily.merge(family(id), 1, Integer::sum);
			assertEquals(id + ": keys of the golden case", CASE_FIELDS, fieldNames(goldenCase));
			ZoneTariff tariff = tariffs.get(goldenCase.get("zone_id").asText());
			assertNotNull(id + ": unknown zone " + goldenCase.get("zone_id"), tariff);
			double decayMeters = goldenCase.get("garage_decay_m").doubleValue();
			GarageOptionModel model = modelByDecay.computeIfAbsent(decayMeters,
					decay -> new GarageOptionModel(garages, decay, maximumDistanceMeters));
			if (goldenCase.get("expected_error").booleanValue()) {
				errorCases++;
				try {
					Result result = evaluate(goldenCase, tariff, model, counters);
					failures.add(id + ": expected IllegalArgumentException, got " + result);
				} catch (IllegalArgumentException expected) {
					assertTrue(id + ": " + expected.getMessage(), expected.getMessage().contains("before arrival_s"));
				}
				continue;
			}
			ParkingOutcome expectedOutcome = ParkingOutcome.valueOf(goldenCase.get("expected_outcome").asText());
			pinnedOutcomes.add(expectedOutcome);
			if (expectedOutcome == ParkingOutcome.PAID_EXPECTED) {
				expectedCases++;
			}
			Result result = evaluate(goldenCase, tariff, model, counters);
			if (result.cents() != goldenCase.get("expected_cents").longValue()) {
				failures.add(id + ": expected " + goldenCase.get("expected_cents").longValue() + " ct, got " + result.cents()
						+ " ct");
			}
			if (result.outcome() != expectedOutcome) {
				failures.add(id + ": expected outcome " + expectedOutcome + ", got " + result.outcome());
			}
			double expectedProbability = goldenCase.get("expected_garage_probability").doubleValue();
			if (Math.abs(result.garageProbability() - expectedProbability) > PROBABILITY_TOLERANCE) {
				failures.add(id + ": expected garage probability " + expectedProbability + ", got "
						+ result.garageProbability());
			}
			if (result.expectedCents().isPresent()) {
				double fraction = result.expectedCents().getAsDouble() - Math.floor(result.expectedCents().getAsDouble());
				if (Math.abs(fraction - 0.5) < ROUNDING_MARGIN_CENTS) {
					failures.add(id + ": the unrounded expectation " + result.expectedCents().getAsDouble()
							+ " lies within " + ROUNDING_MARGIN_CENTS + " of a half cent");
				}
			}
		}
		assertTrue("mismatching golden cases: " + String.join("; ", failures), failures.isEmpty());
		assertEquals(new TreeMap<>(CASES_PER_FAMILY), casesPerFamily);
		assertEquals(1, errorCases);
		// The primary path is taken: the cases that pin PAID_EXPECTED are the stays the calculator priced as an expectation.
		assertTrue("the golden file pins no PAID_EXPECTED case", expectedCases > 0);
		assertEquals(expectedCases, counters.snapshot().expected());
		assertEquals(EnumSet.complementOf(EnumSet.of(ParkingOutcome.NO_ZONE)), pinnedOutcomes);
	}

	@Test
	public void reproducesEveryGarageOptionCaseToTheCent() throws Exception {
		JsonNode golden = ParkingCostCalculatorTest.readResource(ParkingCostCalculatorTest.GOLDEN_CASES_RESOURCE);
		Map<String, GarageTariff> garages = new HashMap<>();
		for (JsonNode garage : golden.get("garages")) {
			GarageTariff parsed = ParkingTariffs.parseGarage(garage);
			garages.put(parsed.garageId(), parsed);
		}
		JsonNode cases = golden.get("garage_option_cases");
		assertEquals(69, cases.size());
		GarageOptionCounters counters = new GarageOptionCounters();
		Set<String> ids = new TreeSet<>();
		Set<String> garagesPriced = new TreeSet<>();
		List<String> failures = new ArrayList<>();
		for (JsonNode optionCase : cases) {
			String id = optionCase.get("id").asText();
			assertTrue("duplicate option case id " + id, ids.add(id));
			assertEquals(id + ": keys of the option case", OPTION_CASE_FIELDS, fieldNames(optionCase));
			GarageTariff garage = garages.get(optionCase.get("garage_id").asText());
			assertNotNull(id + ": unknown garage " + optionCase.get("garage_id"), garage);
			garagesPriced.add(garage.garageId());
			long cents = GarageOptionPricing.optionCents(garage, optionCase.get("arrival_s").doubleValue(),
					optionCase.get("departure_s").doubleValue(), optionCase.get("purpose").asText(), counters);
			if (cents != optionCase.get("expected_cents").longValue()) {
				failures.add(id + " (" + garage.garageId() + "): expected " + optionCase.get("expected_cents").longValue()
						+ " ct, got " + cents + " ct");
			}
		}
		assertTrue("mismatching garage option cases: " + String.join("; ", failures), failures.isEmpty());
		assertEquals(69, counters.snapshot().optionEvaluations());
		// The three tariff structures, the tiered garage with a grace period and the free schedule are all priced.
		assertTrue(garagesPriced.containsAll(Set.of("fx_g01_core", "fx_g07_tiers", "fx_g08_bands", "fx_g15_tier_grace",
				"fx_g17_free", "fx_g18_grace_total")));
		// The closed schedule that was repeated per started 24 h (ASSUMPTION P9) is counted, never silent.
		assertTrue(counters.snapshot().closedScheduleRepeats() > 0);
	}

	@Test
	public void aStayAtADecayOfZeroNeverAsksForTheGarageOptions() throws Exception {
		ZoneTariff zone = ParkingCostCalculatorTest.goldenTariffs().get("fx_bs_ib");
		AtomicInteger lookups = new AtomicInteger();
		GarageOptionCounters counters = new GarageOptionCounters();
		Result result = GarageParkingCostCalculator.cents(zone, 36000, 39600, "shop", false, false, false, 0.0, () -> {
			lookups.incrementAndGet();
			return new GarageOptionModel.Option[0];
		}, counters);
		assertEquals(0, lookups.get());
		assertEquals(180, result.cents());
		assertEquals(ParkingOutcome.PAID_METERED, result.outcome());
		assertEquals(0.0, result.garageProbability(), 0.0);
		assertEquals(0, result.garagesInRange());
		assertEquals(1, counters.snapshot().stays());
		assertEquals(0, counters.snapshot().eligible());
	}

	@Test
	public void theEarlyRulesAndTheCampusNeverAskForTheGarageOptions() throws Exception {
		Map<String, ZoneTariff> tariffs = ParkingCostCalculatorTest.goldenTariffs();
		AtomicInteger lookups = new AtomicInteger();
		GarageParkingCostCalculator.OptionSource counting = () -> {
			lookups.incrementAndGet();
			return new GarageOptionModel.Option[0];
		};
		ZoneTariff street = tariffs.get("fx_bs_ib_v2");
		assertEquals(ParkingOutcome.HOME, GarageParkingCostCalculator
				.cents(street, 36000, 39600, "home", false, false, false, 400.0, counting, null).outcome());
		assertEquals(ParkingOutcome.EMPLOYER_FREE, GarageParkingCostCalculator
				.cents(street, 36000, 39600, "work", true, false, false, 400.0, counting, null).outcome());
		assertEquals(ParkingOutcome.OUTSIDE_FEE_HOURS, GarageParkingCostCalculator
				.cents(street, 0, 3600, "shop", false, false, false, 400.0, counting, null).outcome());
		ZoneTariff campus = tariffs.get("fx_campus");
		assertEquals(ParkingOutcome.PAID_CAMPUS_GUEST, GarageParkingCostCalculator
				.cents(campus, 36000, 39600, "shop", false, false, false, 400.0, counting, null).outcome());
		assertEquals(0, lookups.get());
	}

	@Test
	public void aStayWithoutAGarageInRangeIsPricedAsBeforeAndCounted() throws Exception {
		ZoneTariff zone = ParkingCostCalculatorTest.goldenTariffs().get("fx_bs_ib_v2");
		GarageOptionCounters counters = new GarageOptionCounters();
		Result result = GarageParkingCostCalculator.cents(zone, 36000, 39600, "shop", false, false, false, 400.0,
				() -> new GarageOptionModel.Option[0], counters);
		ParkingCostCalculator.Result plain = ParkingCostCalculator.cents(zone, 36000, 39600, "shop", false, false, false);
		assertEquals(plain.cents(), result.cents());
		assertEquals(plain.outcome(), result.outcome());
		assertEquals(0.0, result.garageProbability(), 0.0);
		GarageOptionCounters.Snapshot snapshot = counters.snapshot();
		assertEquals(1, snapshot.stays());
		assertEquals(1, snapshot.eligible());
		assertEquals(0, snapshot.withGaragesInRange());
		assertFalse(result.expectedCents().isPresent());
	}

	@Test
	public void rejectsAnInvalidDecay() throws Exception {
		ZoneTariff zone = ParkingCostCalculatorTest.goldenTariffs().get("fx_bs_ib_v2");
		for (double decay : new double[] { -1.0, Double.NaN, Double.POSITIVE_INFINITY }) {
			assertThrows("decay " + decay, IllegalArgumentException.class, () -> GarageParkingCostCalculator.cents(zone, 36000,
					39600, "shop", false, false, false, decay, () -> new GarageOptionModel.Option[0], null));
		}
	}

	/** One golden case as braunschweig.parking.golden_cases.evaluate_case_detail evaluates it. */
	private static Result evaluate(JsonNode goldenCase, ZoneTariff tariff, GarageOptionModel model,
			GarageOptionCounters counters) {
		double arrival_s = goldenCase.get("arrival_s").doubleValue();
		double departure_s;
		if (goldenCase.get("terminal").booleanValue()) {
			departure_s = ParkingCostCalculator.terminalDeparture_s(arrival_s, tariff.feeEnd_s());
		} else {
			departure_s = goldenCase.get("departure_s").doubleValue();
		}
		int minimumStay_s = 60 * goldenCase.get("minimum_stay_min").intValue();
		double pricedDeparture_s = ParkingCostCalculator.minimumStayDeparture_s(arrival_s, departure_s, minimumStay_s);
		JsonNode destinationX = goldenCase.get("destination_x_m");
		GarageParkingCostCalculator.OptionSource options = destinationX.isNull() ? () -> new GarageOptionModel.Option[0]
				: () -> model.optionsWithin(destinationX.doubleValue(), goldenCase.get("destination_y_m").doubleValue());
		return GarageParkingCostCalculator.cents(tariff, arrival_s, pricedDeparture_s, goldenCase.get("purpose").asText(),
				goldenCase.get("parking_free").booleanValue(), goldenCase.get("resident_of_zone").booleanValue(),
				goldenCase.get("resident_of_district").booleanValue(), model.decayMeters(), options, counters);
	}

	private static String family(String id) {
		return id.endsWith("Z") ? id.substring(0, 1) + "Z" : id.substring(0, 1);
	}

	private static Set<String> fieldNames(JsonNode node) {
		Set<String> names = new TreeSet<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}
}
