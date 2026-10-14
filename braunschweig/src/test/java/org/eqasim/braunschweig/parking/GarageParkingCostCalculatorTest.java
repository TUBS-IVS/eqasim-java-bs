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
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.eqasim.braunschweig.parking.GarageParkingCostCalculator.Result;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The garage options of a stay (spec Amendments E and H of parking cost zones v2 and spec v3 A1, A2 and A5, eqasim-bs issue
 * #436) against the golden file {@code parking/parking_golden_cases.json} of schema 6, a byte-for-byte copy of the file the
 * Python reference writes (eqasim-bs tests/fixtures/parking; the SHA-256 of the LF text is pinned below, so a changed copy
 * fails here). Every case of {@code cases} (the 100 cases of the families G, L, LZ, V, R and the cases F01..F03, X01..X06 and
 * X09 without a destination, E01..E38 and the cases F04..F08, P01..P06, X07 and X08 with garage options: the expected cost
 * over the street and the garage options, E33..E38 with a separate decay length for the commuter purposes, F with the maximum-stay
 * feasibility, P with the price in the option weights, X with the EV exemption) is priced as
 * braunschweig.parking.golden_cases.evaluate_case_detail prices it, to the cent, with the same outcome and the garage
 * probability within 1e-9. THE PER-CASE VALUES ARE USED, never those of the model: a case carries its own ev_exempt, its own
 * option_price_beta_u_per_eur (0.0 for the 128 earlier cases, whose numbers are the v2 values, while the fixture model says
 * 0.310998) and its own decays (the cases without a destination carry 400 / 400, the model says 400 / 250). Every case of
 * {@code garage_option_cases} (O01..O69 and the band shapes S01..S25) pins the price of one garage option under the three
 * tariff structures. A failing test lists every mismatching case id.
 */
public class GarageParkingCostCalculatorTest {
	/** SHA-256 of the golden file with LF line endings, as the Python reference's blob. */
	private static final String GOLDEN_SHA256 = "ac635f3b64a56fb7dd60e2abbb457a7efcdf14003efc02739679ed7b95bea080";

	/** SHA-256 of the schema-5 tariff model fixture with LF line endings. */
	private static final String FIXTURE_SHA256 = "f51c7341a769c84c25d6dffd11c8ebe3ae2462c9b696eed56773f0ca5967a7cd";

	private static final String FIXTURE_RESOURCE = "/parking/parking_tariffs_fixture.json";

	private static final int GOLDEN_SCHEMA_VERSION = 6;
	private static final double PROBABILITY_TOLERANCE = 1e-9;

	/**
	 * A pinned expectation keeps this distance in cents from a half cent (the Python generator asserts it), so that the
	 * StrictMath.exp of Java cannot flip the rounding; the port checks the same margin on its own unrounded expectation.
	 */
	private static final double ROUNDING_MARGIN_CENTS = 1e-6;

	private static final Map<String, Integer> CASES_PER_FAMILY = Map.of("G", 38, "L", 8, "LZ", 8, "V", 23, "R", 13, "E",
			38, "F", 8, "P", 6, "X", 9);

	private static final Set<String> CASE_FIELDS = Set.of("id", "zone_id", "arrival_s", "departure_s", "purpose",
			"parking_free", "resident_of_zone", "resident_of_district", "ev_exempt", "terminal", "minimum_stay_min",
			"destination_x_m", "destination_y_m", "garage_decay_m", "garage_decay_commute_m", "option_price_beta_u_per_eur",
			"expected_cents", "expected_outcome", "expected_garage_probability", "expected_error");

	/** The two decay lengths of one golden case: lambda of every purpose but work and education, lambda_c of those two. */
	private record Decays(double decayMeters, double commuteDecayMeters) {
	}

	private static final Set<String> OPTION_CASE_FIELDS = Set.of("id", "garage_id", "arrival_s", "departure_s", "purpose",
			"expected_cents");

	/** O01..O69 and the band shapes S01..S25 of the garage release dataset (spec v3 R-FW-2). */
	private static final int GARAGE_OPTION_CASES = 69 + 25;

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
	public void theGoldenGaragesAreTheGaragesOfTheSchemaFiveFixtureModel() throws Exception {
		JsonNode golden = ParkingCostCalculatorTest.readResource(ParkingCostCalculatorTest.GOLDEN_CASES_RESOURCE);
		JsonNode fixture = ParkingCostCalculatorTest.readResource(FIXTURE_RESOURCE);
		assertEquals(fixture.get("garages"), golden.get("garages"));
		assertEquals(fixture.get("garage_max_distance_m"), golden.get("garage_max_distance_m"));
		assertEquals(fixture.get("zones"), golden.get("tariffs"));
		assertEquals(5, fixture.get("schema_version").intValue());
		assertEquals(400.0, fixture.get("garage_decay_m").doubleValue(), 0.0);
		assertEquals(250.0, fixture.get("garage_decay_commute_m").doubleValue(), 0.0);
		// The money coefficient of the model and of the golden file, the one of the mode choice (spec v3 A2, ASSUMPTION G1-d).
		assertEquals(0.310998, fixture.get("option_price_beta_u_per_eur").doubleValue(), 0.0);
		assertEquals(0.310998, golden.get("option_price_beta_u_per_eur").doubleValue(), 0.0);
		assertEquals(38, golden.get("garages").size());
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
		Map<Decays, GarageOptionModel> modelByDecay = new HashMap<>();
		GarageOptionCounters counters = new GarageOptionCounters();
		JsonNode cases = golden.get("cases");
		assertEquals(CASES_PER_FAMILY.values().stream().mapToInt(Integer::intValue).sum(), cases.size());
		Map<String, Integer> casesPerFamily = new TreeMap<>();
		Set<String> ids = new HashSet<>();
		Set<ParkingOutcome> pinnedOutcomes = EnumSet.noneOf(ParkingOutcome.class);
		List<String> failures = new ArrayList<>();
		int expectedCases = 0;
		int noFeasibleOptionCases = 0;
		int errorCases = 0;
		for (JsonNode goldenCase : cases) {
			String id = goldenCase.get("id").asText();
			assertTrue("duplicate golden case id " + id, ids.add(id));
			casesPerFamily.merge(family(id), 1, Integer::sum);
			assertEquals(id + ": keys of the golden case", CASE_FIELDS, fieldNames(goldenCase));
			ZoneTariff tariff = tariffs.get(goldenCase.get("zone_id").asText());
			assertNotNull(id + ": unknown zone " + goldenCase.get("zone_id"), tariff);
			Decays decays = new Decays(number(goldenCase, "garage_decay_m"), number(goldenCase, "garage_decay_commute_m"));
			GarageOptionModel model = modelByDecay.computeIfAbsent(decays, key -> new GarageOptionModel(garages,
					key.decayMeters(), key.commuteDecayMeters(), maximumDistanceMeters));
			if (ParkingCostCalculatorTest.literalBoolean(goldenCase, "expected_error")) {
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
			if (expectedOutcome == ParkingOutcome.NO_FEASIBLE_OPTION) {
				noFeasibleOptionCases++;
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
		// Spec v3 A1: every stay pinned NO_FEASIBLE_OPTION was counted as such (with and without a garage in range), and the
		// feasibility test dropped garage options (F04: a 6 h stay above the maximum stay of its garage), never silently.
		assertEquals(3, noFeasibleOptionCases);
		assertEquals(noFeasibleOptionCases, counters.snapshot().noFeasibleOption());
		assertTrue("no garage option was left out by a maximum stay", counters.snapshot().optionsInfeasible() > 0);
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
		assertEquals(GARAGE_OPTION_CASES, cases.size());
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
		assertEquals(GARAGE_OPTION_CASES, counters.snapshot().optionEvaluations());
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
		Result result = GarageParkingCostCalculator.cents(zone, 36000, 39600, "shop", false, false, false, false, 0.0, 0.0,
				0.0, () -> {
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
				.cents(street, 36000, 39600, "home", false, false, false, false, 400.0, 400.0, 0.0, counting, null).outcome());
		assertEquals(ParkingOutcome.EMPLOYER_FREE, GarageParkingCostCalculator
				.cents(street, 36000, 39600, "work", true, false, false, false, 400.0, 400.0, 0.0, counting, null).outcome());
		assertEquals(ParkingOutcome.OUTSIDE_FEE_HOURS, GarageParkingCostCalculator
				.cents(street, 0, 3600, "shop", false, false, false, false, 400.0, 400.0, 0.0, counting, null).outcome());
		ZoneTariff campus = tariffs.get("fx_campus");
		assertEquals(ParkingOutcome.PAID_CAMPUS_GUEST, GarageParkingCostCalculator
				.cents(campus, 36000, 39600, "shop", false, false, false, false, 400.0, 400.0, 0.0, counting, null).outcome());
		assertEquals(0, lookups.get());
	}

	@Test
	public void aStayWithoutAGarageInRangeIsPricedAsBeforeAndCounted() throws Exception {
		ZoneTariff zone = ParkingCostCalculatorTest.goldenTariffs().get("fx_bs_ib_v2");
		GarageOptionCounters counters = new GarageOptionCounters();
		Result result = GarageParkingCostCalculator.cents(zone, 36000, 39600, "shop", false, false, false, false, 400.0, 400.0,
				0.0, () -> new GarageOptionModel.Option[0], counters);
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
			IllegalArgumentException error = assertThrows("decay " + decay, IllegalArgumentException.class,
					() -> GarageParkingCostCalculator.cents(zone, 36000, 39600, "shop", false, false, false, false, decay,
							250.0, 0.0, () -> new GarageOptionModel.Option[0], null));
			assertTrue(error.getMessage(), error.getMessage().contains("decayMeters must be a finite number"));
		}
	}

	/** The commuter decay is validated like the other one, for every purpose (a stay of another purpose fails too). */
	@Test
	public void rejectsAnInvalidCommuteDecayForEveryPurpose() throws Exception {
		ZoneTariff zone = ParkingCostCalculatorTest.goldenTariffs().get("fx_bs_ib_v2");
		for (String purpose : new String[] { "work", "shop" }) {
			for (double commuteDecay : new double[] { -1.0, Double.NaN, Double.NEGATIVE_INFINITY }) {
				IllegalArgumentException error = assertThrows(purpose + " " + commuteDecay, IllegalArgumentException.class,
						() -> GarageParkingCostCalculator.cents(zone, 36000, 39600, purpose, false, false, false, false, 400.0,
								commuteDecay, 0.0, () -> new GarageOptionModel.Option[0], null));
				assertTrue(error.getMessage(), error.getMessage().contains("commuteDecayMeters must be a finite number"));
			}
		}
	}

	/**
	 * One helper decides which decay governs a stay (spec Amendment H1, the Python reference
	 * garage_decay_m_for_purpose): the commuter decay for work and education, lambda for every other purpose; the purpose is
	 * the MATSim activity type, matched exactly.
	 */
	@Test
	public void theDecayOfAStayDependsOnItsPurposeExactlyLikeThePythonHelper() {
		assertEquals(250.0, GarageParkingCostCalculator.decayMetersForPurpose("work", 400.0, 250.0), 0.0);
		assertEquals(250.0, GarageParkingCostCalculator.decayMetersForPurpose("education", 400.0, 250.0), 0.0);
		for (String purpose : new String[] { "shop", "leisure", "other", "home", "Work", "WORK", "work ", "" }) {
			assertEquals("purpose '" + purpose + "'", 400.0,
					GarageParkingCostCalculator.decayMetersForPurpose(purpose, 400.0, 250.0), 0.0);
		}
		assertEquals(0.0, GarageParkingCostCalculator.decayMetersForPurpose("work", 400.0, 0.0), 0.0);
		assertEquals(0.0, GarageParkingCostCalculator.decayMetersForPurpose("shop", 0.0, 250.0), 0.0);
		assertEquals(ParkingCostCalculator.COMMUTER_PURPOSES, Set.of("work", "education"));
	}

	/**
	 * Spec Amendment H1, hand-derived: the same destination, one garage 300 m away, the street open and not free. A shopping
	 * stay weighs the garage with exp(-300 / 400) (lambda 400 m), a work stay with exp(-300 / 250) (lambda_c 250 m), so the
	 * garage probability of the work stay is lower, exactly w / (1 + w) with the weight of its own decay.
	 */
	@Test
	public void aWorkStayAndAShoppingStayAtTheSameDestinationUseTheirOwnDecay() throws Exception {
		JsonNode golden = ParkingCostCalculatorTest.readResource(ParkingCostCalculatorTest.GOLDEN_CASES_RESOURCE);
		GarageTariff garage = ParkingTariffs.parseGarage(golden.get("garages").get(0));
		GarageOptionModel.Option[] options = { new GarageOptionModel.Option(garage, 300.0) };
		ZoneTariff zone = ParkingCostCalculatorTest.goldenTariffs().get("fx_bs_ib");
		Result shopping = GarageParkingCostCalculator.cents(zone, 36000, 39600, "shop", false, false, false, false, 400.0,
				250.0, 0.0, () -> options, null);
		Result work = GarageParkingCostCalculator.cents(zone, 36000, 39600, "work", false, false, false, false, 400.0, 250.0,
				0.0, () -> options, null);
		Result education = GarageParkingCostCalculator.cents(zone, 36000, 39600, "education", false, false, false, false,
				400.0, 250.0, 0.0, () -> options, null);
		double visitorWeight = StrictMath.exp(-300.0 / 400.0);
		double commuterWeight = StrictMath.exp(-300.0 / 250.0);
		assertEquals(visitorWeight / (1.0 + visitorWeight), shopping.garageProbability(), 0.0);
		assertEquals(commuterWeight / (1.0 + commuterWeight), work.garageProbability(), 0.0);
		assertEquals(work.garageProbability(), education.garageProbability(), 0.0);
		assertTrue(work.garageProbability() < shopping.garageProbability());
		assertEquals(ParkingOutcome.PAID_EXPECTED, shopping.outcome());
		assertEquals(ParkingOutcome.PAID_EXPECTED, work.outcome());
	}

	/**
	 * Spec Amendment H1: the options act for a purpose iff the decay that governs it is above 0. With lambda_c 0 a work stay is
	 * priced as before (street product, no lookup of the options, not eligible) while a shopping stay at the same destination
	 * is still priced as an expectation; with lambda 0 and lambda_c above 0 it is the other way round.
	 */
	@Test
	public void theOptionsActForAPurposeOnlyWhenItsOwnDecayIsAboveZero() throws Exception {
		JsonNode golden = ParkingCostCalculatorTest.readResource(ParkingCostCalculatorTest.GOLDEN_CASES_RESOURCE);
		GarageTariff garage = ParkingTariffs.parseGarage(golden.get("garages").get(0));
		GarageOptionModel.Option[] options = { new GarageOptionModel.Option(garage, 300.0) };
		ZoneTariff zone = ParkingCostCalculatorTest.goldenTariffs().get("fx_bs_ib");
		for (boolean commuterOff : new boolean[] { true, false }) {
			double decay = commuterOff ? 400.0 : 0.0;
			double commuteDecay = commuterOff ? 0.0 : 250.0;
			String switchedOff = commuterOff ? "work" : "shop";
			String acting = commuterOff ? "shop" : "work";
			AtomicInteger lookups = new AtomicInteger();
			GarageParkingCostCalculator.OptionSource counting = () -> {
				lookups.incrementAndGet();
				return options;
			};
			GarageOptionCounters counters = new GarageOptionCounters();
			Result off = GarageParkingCostCalculator.cents(zone, 36000, 39600, switchedOff, false, false, false, false, decay,
					commuteDecay, 0.0, counting, counters);
			ParkingCostCalculator.Result plain = ParkingCostCalculator.cents(zone, 36000, 39600, switchedOff, false, false,
					false);
			assertEquals(switchedOff, plain.cents(), off.cents());
			assertEquals(switchedOff, plain.outcome(), off.outcome());
			assertEquals(0.0, off.garageProbability(), 0.0);
			assertEquals(0, lookups.get());
			assertEquals(0, counters.snapshot().eligible());
			Result on = GarageParkingCostCalculator.cents(zone, 36000, 39600, acting, false, false, false, false, decay,
					commuteDecay, 0.0, counting, counters);
			assertEquals(acting, ParkingOutcome.PAID_EXPECTED, on.outcome());
			assertTrue(acting, on.garageProbability() > 0.0);
			assertEquals(1, lookups.get());
			assertEquals(2, counters.snapshot().stays());
			assertEquals(1, counters.snapshot().eligible());
		}
	}

	/** Both decays 0 switches the options off for every purpose. */
	@Test
	public void bothDecaysAtZeroSwitchTheOptionsOffForEveryPurpose() throws Exception {
		ZoneTariff zone = ParkingCostCalculatorTest.goldenTariffs().get("fx_bs_ib");
		AtomicInteger lookups = new AtomicInteger();
		for (String purpose : new String[] { "work", "education", "shop" }) {
			Result result = GarageParkingCostCalculator.cents(zone, 36000, 39600, purpose, false, false, false, false, 0.0,
					0.0, 0.0, () -> {
						lookups.incrementAndGet();
						return new GarageOptionModel.Option[0];
					}, null);
			assertEquals(purpose, ParkingCostCalculator.cents(zone, 36000, 39600, purpose, false, false, false).cents(),
					result.cents());
		}
		assertEquals(0, lookups.get());
	}

	/**
	 * When every weight underflows to 0 the mixture has no defined probabilities (the sum of the weights is 0): the stay fails
	 * with the decay and the distances instead of being priced 0 ct PAID_EXPECTED with a NaN probability. Here the street is
	 * unavailable (golden zone fx_bs_ia_v2, a 4 h stay above its maximum stay of 180 min, no long-stay product) and the one
	 * garage lies 1000 m away at a decay of 1 m (exp(-1000) = 0). With an available street the street weight 1 keeps the
	 * sum positive and the stay is priced as before.
	 */
	@Test
	public void failsWhenEveryWeightUnderflows() throws Exception {
		Map<String, ZoneTariff> tariffs = ParkingCostCalculatorTest.goldenTariffs();
		JsonNode golden = ParkingCostCalculatorTest.readResource(ParkingCostCalculatorTest.GOLDEN_CASES_RESOURCE);
		GarageTariff garage = ParkingTariffs.parseGarage(golden.get("garages").get(0));
		GarageOptionModel.Option[] farGarage = { new GarageOptionModel.Option(garage, 1000.0) };

		// An IllegalStateException that names the zone, the stay, the decays and the garage (the A2 deferred minor of the plan).
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> GarageParkingCostCalculator.cents(tariffs.get("fx_bs_ia_v2"), 32400, 46800, "shop", false, false, false,
						false, 1.0, 1.0, 0.0, () -> farGarage, null));
		for (String fragment : new String[] { "zone fx_bs_ia_v2", "[32400, 46800) s", "purpose shop", "decay 1.0 m",
				"garage_decay_m 1.0 m", "garage_decay_commute_m 1.0 m", "1000.0", garage.garageId(), "street option absent" }) {
			assertTrue("'" + fragment + "' missing in: " + error.getMessage(), error.getMessage().contains(fragment));
		}

		Result priced = GarageParkingCostCalculator.cents(tariffs.get("fx_bs_ib"), 36000, 39600, "shop", false, false, false,
				false, 1.0, 1.0, 0.0, () -> farGarage, null);
		assertEquals(ParkingOutcome.PAID_EXPECTED, priced.outcome());
		assertEquals(180, priced.cents());
		assertEquals(0.0, priced.garageProbability(), 0.0);
	}

	/**
	 * A paid garage whose weight is far below 1e-16 of the total (here a garage at 120 EUR for the hour: exp(-0.310998 * 120) =
	 * 6e-17) next to a free garage and the street. The paid part of the garage probability is accumulated directly, so it is
	 * exactly the tiny positive share, never a difference of two sums that could come out at -1e-17 and make the report counter
	 * throw inside the car cost model; the stay is accepted by {@link ParkingOutcomeCounter.PricedStay}.
	 */
	@Test
	public void aTinyPaidGarageWeightGivesATinyNonNegativePaidProbabilityThatTheCounterAccepts() throws Exception {
		ZoneTariff zone = ParkingCostCalculatorTest.goldenTariffs().get("fx_bs_ib");
		GarageOptionModel.Option[] options = {
				new GarageOptionModel.Option(nightOnlyGarage("g_a_free", 200), 0.0),
				new GarageOptionModel.Option(hourlyGarage("g_b_dear", 12000, OptionalInt.empty()), 0.0) };
		double beta = 0.310998;
		Result result = GarageParkingCostCalculator.cents(zone, 36000, 39600, "shop", false, false, false, false, 400.0, 250.0,
				beta, () -> options, null);
		double streetWeight = StrictMath.exp(-0.0 / 400.0 - beta * 180 / 100);
		double freeWeight = StrictMath.exp(-0.0 / 400.0 - beta * 0 / 100);
		double dearWeight = StrictMath.exp(-0.0 / 400.0 - beta * 12000 / 100);
		double total = streetWeight + freeWeight + dearWeight;
		assertTrue("the paid weight must be below 1e-16 for this test: " + dearWeight, dearWeight < 1e-16);
		assertEquals(dearWeight / total, result.garageProbabilityPaid(), 0.0);
		assertTrue(result.garageProbabilityPaid() > 0.0);
		assertEquals(freeWeight / total, result.garageProbabilityFree(), 0.0);
		ParkingOutcomeCounter.PricedStay stay = new ParkingOutcomeCounter.PricedStay(result.outcome(), "fx_bs_ib", "shop",
				result.expectedCents().getAsDouble(), result.garageProbabilityPaid(), result.garageProbabilityFree(),
				result.garagesInRange(), !result.streetFeasible());
		new ParkingOutcomeCounter().record(stay);
	}

	/**
	 * The weights use StrictMath.exp (fdlibm), so that the expectation is bit-identical on every JVM and CPU: the probability
	 * of one garage at 300 m and decay 400 m is exactly w / (1 + w) with w = StrictMath.exp(-300 / 400).
	 */
	@Test
	public void theWeightsAreComputedWithStrictMath() throws Exception {
		JsonNode golden = ParkingCostCalculatorTest.readResource(ParkingCostCalculatorTest.GOLDEN_CASES_RESOURCE);
		GarageTariff garage = ParkingTariffs.parseGarage(golden.get("garages").get(0));
		GarageOptionModel.Option[] options = { new GarageOptionModel.Option(garage, 300.0) };
		Result result = GarageParkingCostCalculator.cents(ParkingCostCalculatorTest.goldenTariffs().get("fx_bs_ib"), 36000,
				39600, "shop", false, false, false, false, 400.0, 400.0, 0.0, () -> options, null);
		double weight = StrictMath.exp(-300.0 / 400.0);
		assertEquals(weight / (1.0 + weight), result.garageProbability(), 0.0);
	}

	/** One golden case as braunschweig.parking.golden_cases.evaluate_case_detail evaluates it. */
	private static Result evaluate(JsonNode goldenCase, ZoneTariff tariff, GarageOptionModel model,
			GarageOptionCounters counters) {
		double arrival_s = number(goldenCase, "arrival_s");
		double departure_s;
		if (ParkingCostCalculatorTest.literalBoolean(goldenCase, "terminal")) {
			departure_s = ParkingCostCalculator.terminalDeparture_s(arrival_s, tariff.feeEnd_s());
		} else {
			departure_s = number(goldenCase, "departure_s");
		}
		int minimumStay_s = 60 * goldenCase.get("minimum_stay_min").intValue();
		double pricedDeparture_s = ParkingCostCalculator.minimumStayDeparture_s(arrival_s, departure_s, minimumStay_s);
		JsonNode destinationX = goldenCase.get("destination_x_m");
		GarageParkingCostCalculator.OptionSource options = destinationX.isNull() ? () -> new GarageOptionModel.Option[0]
				: () -> model.optionsWithin(number(goldenCase, "destination_x_m"), number(goldenCase, "destination_y_m"));
		// The per-case ev_exempt and option_price_beta_u_per_eur: never those of the document or of the model (the 128 cases of
		// schema 5 carry beta 0.0 and their numbers are the v2 values, while the model of the fixture says 0.310998).
		return GarageParkingCostCalculator.cents(tariff, arrival_s, pricedDeparture_s, goldenCase.get("purpose").asText(),
				ParkingCostCalculatorTest.literalBoolean(goldenCase, "parking_free"),
				ParkingCostCalculatorTest.literalBoolean(goldenCase, "resident_of_zone"),
				ParkingCostCalculatorTest.literalBoolean(goldenCase, "resident_of_district"),
				ParkingCostCalculatorTest.literalBoolean(goldenCase, "ev_exempt"), model.decayMeters(),
				model.commuteDecayMeters(), number(goldenCase, "option_price_beta_u_per_eur"), options, counters);
	}

	/** A numeric field of a golden case: a JSON null or a string fails instead of silently reading as 0.0. */
	private static double number(JsonNode goldenCase, String field) {
		JsonNode value = goldenCase.get(field);
		assertTrue(goldenCase.get("id") + ": " + field + " must be a JSON number, got " + value,
				value != null && value.isNumber());
		return value.doubleValue();
	}

	/** A garage at the origin that charges {@code hourlyRateCents} per started hour all day, with an optional maximum stay. */
	private static GarageTariff hourlyGarage(String id, long hourlyRateCents, OptionalInt maxStayMinutes) {
		return new GarageTariff(id, 0.0, 0.0, OptionalLong.of(hourlyRateCents), OptionalInt.of(60), OptionalInt.of(0),
				OptionalInt.of(86400), OptionalInt.empty(), OptionalLong.empty(), OptionalInt.empty(), OptionalInt.empty(),
				OptionalLong.empty(), List.of(), List.of(), OptionalLong.empty(), maxStayMinutes);
	}

	/** A garage that charges {@code hourlyRateCents} per started hour only before 01:00, so that a daytime stay costs 0 ct. */
	private static GarageTariff nightOnlyGarage(String id, long hourlyRateCents) {
		return new GarageTariff(id, 0.0, 0.0, OptionalLong.of(hourlyRateCents), OptionalInt.of(60), OptionalInt.of(0),
				OptionalInt.of(3600), OptionalInt.empty(), OptionalLong.empty(), OptionalInt.empty(), OptionalInt.empty(),
				OptionalLong.empty(), List.of(), List.of(), OptionalLong.empty(), OptionalInt.empty());
	}

	/**
	 * Spec v3 A2, ASSUMPTION G1-d, hand-derived: shop stay 10:00-11:00 in fx_bs_ib (street 180 ct for the hour), two garages at
	 * the same distance of 300 m with lambda 400 m, a cheap one (100 ct) and a dear one (300 ct). With beta 0 (the weights of v2)
	 * both weigh exp(-0.75) and carry the same probability. With the money coefficient 0.310998 per EUR the weights are
	 * exp(-0 - 0.310998 * 1.80) for the street, exp(-0.75 - 0.310998 * 1.00) for the cheap and exp(-0.75 - 0.310998 * 3.00) for
	 * the dear garage: the cheap garage is the more probable one. The expectation and the probabilities are summed in the
	 * order street, then the garages by ascending id, in double precision, as the Python reference sums them.
	 */
	@Test
	public void ofTwoGaragesAtTheSameDistanceTheCheaperOneWeighsMoreWithAPriceCoefficient() throws Exception {
		ZoneTariff zone = ParkingCostCalculatorTest.goldenTariffs().get("fx_bs_ib");
		GarageOptionModel.Option[] options = {
			new GarageOptionModel.Option(hourlyGarage("g_a_cheap", 100, OptionalInt.empty()), 300.0),
				new GarageOptionModel.Option(hourlyGarage("g_b_dear", 300, OptionalInt.empty()), 300.0) };
		double beta = 0.310998;

		Result withoutPrice = GarageParkingCostCalculator.cents(zone, 36000, 39600, "shop", false, false, false, false, 400.0,
				250.0, 0.0, () -> options, null);
		double visitorWeight = StrictMath.exp(-300.0 / 400.0);
		double plainTotal = 1.0 + visitorWeight + visitorWeight;
		assertEquals(visitorWeight / plainTotal + visitorWeight / plainTotal, withoutPrice.garageProbability(), 0.0);

		Result priced = GarageParkingCostCalculator.cents(zone, 36000, 39600, "shop", false, false, false, false, 400.0, 250.0,
				beta, () -> options, null);
		double streetWeight = StrictMath.exp(-0.0 / 400.0 - beta * 180 / 100);
		double cheapWeight = StrictMath.exp(-300.0 / 400.0 - beta * 100 / 100);
		double dearWeight = StrictMath.exp(-300.0 / 400.0 - beta * 300 / 100);
		double total = streetWeight + cheapWeight + dearWeight;
		double cheapProbability = cheapWeight / total;
		double dearProbability = dearWeight / total;
		assertTrue(cheapProbability > dearProbability);
		assertEquals(cheapProbability + dearProbability, priced.garageProbability(), 0.0);
		double expected = 0.0 + streetWeight / total * 180 + cheapProbability * 100 + dearProbability * 300;
		assertEquals(expected, priced.expectedCents().getAsDouble(), 0.0);
		assertEquals((long) Math.floor(expected + 0.5), priced.cents());
		assertEquals(ParkingOutcome.PAID_EXPECTED, priced.outcome());
		assertTrue(priced.streetFeasible());
		// Every garage costs more than 0 ct for this stay: all of the garage probability is paid.
		assertEquals(0.0, priced.garageProbabilityFree(), 0.0);
		assertEquals(priced.garageProbability(), priced.garageProbabilityPaid(), 0.0);
	}

	/**
	 * Spec v3 A2 and A4: a garage that costs 0 ct for the stay (here a night-only garage, the stay lies outside its window)
	 * carries the FREE part of the garage probability, a garage that costs more the PAID part; both together are the garage
	 * probability. fx_bs_ib: shop stay 10:00-11:00, street 180 ct; free garage and paid garage (200 ct) both at 0 m, lambda
	 * 400 m, beta 0.310998: weights street exp(-0.559...), free garage exp(0) = 1, paid garage exp(-0.622...).
	 */
	@Test
	public void theGarageProbabilityIsSplitIntoThePaidAndTheFreeGarages() throws Exception {
		ZoneTariff zone = ParkingCostCalculatorTest.goldenTariffs().get("fx_bs_ib");
		GarageOptionModel.Option[] options = {
			new GarageOptionModel.Option(hourlyGarage("g_a_paid", 200, OptionalInt.empty()), 0.0),
				new GarageOptionModel.Option(nightOnlyGarage("g_b_free", 200), 0.0) };
		double beta = 0.310998;
		Result result = GarageParkingCostCalculator.cents(zone, 36000, 39600, "shop", false, false, false, false, 400.0, 250.0,
				beta, () -> options, null);
		double streetWeight = StrictMath.exp(-0.0 / 400.0 - beta * 180 / 100);
		double paidWeight = StrictMath.exp(-0.0 / 400.0 - beta * 200 / 100);
		double freeWeight = StrictMath.exp(-0.0 / 400.0 - beta * 0 / 100);
		double total = streetWeight + paidWeight + freeWeight;
		assertEquals(paidWeight / total, result.garageProbabilityPaid(), 1e-15);
		assertEquals(freeWeight / total, result.garageProbabilityFree(), 0.0);
		assertEquals(paidWeight / total + freeWeight / total, result.garageProbability(), 0.0);
		assertTrue(result.garageProbabilityFree() > 0.0 && result.garageProbabilityPaid() > 0.0);
	}

	/**
	 * Spec v3 A1, hand-derived on fx_ia_nolong_v3 (street 180 ct/h, 09:00-20:00, maximum stay 180 min, no long-stay product): a
	 * shop stay 09:30-13:30 is 240 chargeable minutes, so the street is no option. Garage A (maximum stay 120 min) cannot serve
	 * the 240-minute elapsed stay and leaves the option set, garage B (no maximum, 100 ct per started hour = 400 ct) stays:
	 * the stay is priced over B alone, probability 1, 400 ct, PAID_EXPECTED, street infeasible, one option left out.
	 */
	@Test
	public void aGarageAboveItsMaximumStayLeavesTheOptionSetAndTheStreetLeavesItAboveItsMaximum() throws Exception {
		ZoneTariff zone = ParkingCostCalculatorTest.goldenTariffs().get("fx_ia_nolong_v3");
		GarageOptionModel.Option[] options = {
				new GarageOptionModel.Option(hourlyGarage("g_a_max120", 150, OptionalInt.of(120)), 300.0),
				new GarageOptionModel.Option(hourlyGarage("g_b_open", 100, OptionalInt.empty()), 700.0) };
		GarageOptionCounters counters = new GarageOptionCounters();
		Result result = GarageParkingCostCalculator.cents(zone, 34200, 48600, "shop", false, false, false, false, 400.0, 250.0,
				0.0, () -> options, counters);
		assertEquals(ParkingOutcome.PAID_EXPECTED, result.outcome());
		assertEquals(400, result.cents());
		assertEquals(1.0, result.garageProbability(), 1e-15);
		assertFalse(result.streetFeasible());
		assertEquals(2, result.garagesInRange());
		GarageOptionCounters.Snapshot snapshot = counters.snapshot();
		assertEquals(1, snapshot.optionsInfeasible());
		assertEquals(1, snapshot.streetUnavailable());
		assertEquals(0, snapshot.noFeasibleOption());
		assertEquals(1, snapshot.optionEvaluations());
		// The elapsed stay is compared, rounded up to minutes: exactly 120 minutes still fits garage A, one second more does not.
		assertTrue(options[0].garage().admitsElapsedStay(7200));
		assertFalse(options[0].garage().admitsElapsedStay(7201));
		assertTrue(options[1].garage().admitsElapsedStay(Integer.MAX_VALUE));
	}

	/**
	 * Spec v3 A1, ruling R-A-6: with no option left (street above its maximum without a product, the one garage above its own
	 * maximum) the stay is priced as the street at its metered price with the maximum ignored (240 min = 720 ct) and named
	 * NO_FEASIBLE_OPTION, probability 0, no exception; the garage in range is counted, the infeasible option and the stay
	 * without a feasible option are counted too.
	 */
	@Test
	public void aStayWithNoFeasibleOptionIsPricedAsTheStreetAndCounted() throws Exception {
		ZoneTariff zone = ParkingCostCalculatorTest.goldenTariffs().get("fx_ia_nolong_v3");
		GarageOptionModel.Option[] options = {
				new GarageOptionModel.Option(hourlyGarage("g_a_max120", 150, OptionalInt.of(120)), 300.0) };
		GarageOptionCounters counters = new GarageOptionCounters();
		Result result = GarageParkingCostCalculator.cents(zone, 34200, 48600, "shop", false, false, false, false, 400.0, 250.0,
				0.0, () -> options, counters);
		assertEquals(ParkingOutcome.NO_FEASIBLE_OPTION, result.outcome());
		assertEquals(720, result.cents());
		assertEquals(0.0, result.garageProbability(), 0.0);
		assertFalse(result.expectedCents().isPresent());
		assertFalse(result.streetFeasible());
		assertEquals(1, result.garagesInRange());
		GarageOptionCounters.Snapshot snapshot = counters.snapshot();
		assertEquals(1, snapshot.noFeasibleOption());
		assertEquals(1, snapshot.optionsInfeasible());
		assertEquals(1, snapshot.eligible());
		assertEquals(0, snapshot.expected());
		// Without any garage in range the stay is counted too, so the rate is not blind to the zones without a garage.
		GarageOptionCounters noGarages = new GarageOptionCounters();
		Result alone = GarageParkingCostCalculator.cents(zone, 34200, 48600, "shop", false, false, false, false, 400.0, 250.0,
				0.0, () -> new GarageOptionModel.Option[0], noGarages);
		assertEquals(ParkingOutcome.NO_FEASIBLE_OPTION, alone.outcome());
		assertEquals(720, alone.cents());
		assertFalse(alone.streetFeasible());
		assertEquals(1, noGarages.snapshot().noFeasibleOption());
	}

	/**
	 * Spec v3 A1: with the street feasible and every garage above its maximum, the stay is the street alone: its own outcome,
	 * probability 0, no NO_FEASIBLE_OPTION. fx_bs_ib has no maximum stay; a 4 h stay (10:00-14:00 = 720 ct) leaves the two
	 * garages (maximum 120 and 90 min) out.
	 */
	@Test
	public void aFeasibleStreetKeepsItsOwnOutcomeWhenEveryGarageIsAboveItsMaximum() throws Exception {
		ZoneTariff zone = ParkingCostCalculatorTest.goldenTariffs().get("fx_bs_ib");
		GarageOptionModel.Option[] options = {
				new GarageOptionModel.Option(hourlyGarage("g_a", 150, OptionalInt.of(120)), 300.0),
				new GarageOptionModel.Option(hourlyGarage("g_b", 150, OptionalInt.of(90)), 100.0) };
		GarageOptionCounters counters = new GarageOptionCounters();
		Result result = GarageParkingCostCalculator.cents(zone, 36000, 50400, "shop", false, false, false, false, 400.0, 250.0,
				0.31, () -> options, counters);
		assertEquals(ParkingOutcome.PAID_METERED, result.outcome());
		assertEquals(720, result.cents());
		assertEquals(0.0, result.garageProbability(), 0.0);
		assertTrue(result.streetFeasible());
		assertEquals(2, counters.snapshot().optionsInfeasible());
		assertEquals(0, counters.snapshot().noFeasibleOption());
		assertEquals(0, counters.snapshot().expected());
	}

	/**
	 * Spec v3 A5, ruling R-A-10: an exempt stay within ev_free_minutes (240) and the maximum stay is 0 ct, EV_EXEMPT, with
	 * garages in range (the garage probability is 0, the stay counts as a free street, E4); one minute above the limit the stay
	 * is priced as everybody's stay, over the street and the garage.
	 */
	@Test
	public void anExemptStayIsFreeEvenWithGaragesInRangeAndALongerStayPricesAsEverybodys() throws Exception {
		ZoneTariff zone = ParkingCostCalculatorTest.goldenTariffs().get("fx_ev_street_v3");
		GarageOptionModel.Option[] options = {
				new GarageOptionModel.Option(hourlyGarage("g_a", 100, OptionalInt.empty()), 300.0) };
		GarageOptionCounters counters = new GarageOptionCounters();
		Result exempt = GarageParkingCostCalculator.cents(zone, 36000, 36000 + 14400, "shop", false, false, false, true, 400.0,
				250.0, 0.310998, () -> options, counters);
		assertEquals(ParkingOutcome.EV_EXEMPT, exempt.outcome());
		assertEquals(0, exempt.cents());
		assertEquals(0.0, exempt.garageProbability(), 0.0);
		assertEquals(1, exempt.garagesInRange());
		assertEquals(1, counters.snapshot().streetFree());
		assertEquals(0, counters.snapshot().expected());

		Result beyond = GarageParkingCostCalculator.cents(zone, 36000, 36000 + 14460, "shop", false, false, false, true, 400.0,
				250.0, 0.310998, () -> options, counters);
		assertEquals(ParkingOutcome.PAID_EXPECTED, beyond.outcome());
		assertTrue(beyond.garageProbability() > 0.0);
		Result notExempt = GarageParkingCostCalculator.cents(zone, 36000, 36000 + 14460, "shop", false, false, false, false,
				400.0, 250.0, 0.310998, () -> options, null);
		assertEquals(notExempt.cents(), beyond.cents());
		assertEquals(notExempt.garageProbability(), beyond.garageProbability(), 0.0);
	}

	@Test
	public void rejectsAnInvalidOptionPriceCoefficient() throws Exception {
		ZoneTariff zone = ParkingCostCalculatorTest.goldenTariffs().get("fx_bs_ib_v2");
		for (double beta : new double[] { -0.1, Double.NaN, Double.POSITIVE_INFINITY }) {
			IllegalArgumentException error = assertThrows("beta " + beta, IllegalArgumentException.class,
					() -> GarageParkingCostCalculator.cents(zone, 36000, 39600, "shop", false, false, false, false, 400.0,
							250.0, beta, () -> new GarageOptionModel.Option[0], null));
			assertTrue(error.getMessage(), error.getMessage().contains("optionPriceBetaPerEuro must be a finite number >= 0"));
		}
	}

	/** The weight of an option is the exponential of the distance term minus the price term, in this operation order. */
	@Test
	public void theOptionWeightHasTheOperationOrderOfThePythonReference() {
		assertEquals(StrictMath.exp(-300.0 / 400.0), GarageParkingCostCalculator.optionWeight(300.0, 400.0, 250, 0.0), 0.0);
		assertEquals(StrictMath.exp(-300.0 / 400.0 - 0.310998 * 250 / 100),
				GarageParkingCostCalculator.optionWeight(300.0, 400.0, 250, 0.310998), 0.0);
		assertEquals(1.0, GarageParkingCostCalculator.optionWeight(0.0, 400.0, 0, 0.310998), 0.0);
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
