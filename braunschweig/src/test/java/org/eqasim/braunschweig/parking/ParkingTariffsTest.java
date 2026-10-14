package org.eqasim.braunschweig.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.logging.log4j.Level;
import org.eqasim.braunschweig.parking.ParkingLogCapture.Event;
import org.eqasim.braunschweig.parking.ParkingTariffs.ResidentDistrict;
import org.eqasim.braunschweig.parking.ZoneTariff.ZoneType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Reading the parking tariff model JSON (design section 5.4; schema 1 of version 1, schema 2 of the v2 levers and the
 * resident districts, schema 3 of the garage options of spec Amendment E, schema 4 of the separate commuter decay length of
 * spec Amendment H, schema 5 of the v3 mechanics: the option price coefficient, the garage maximum stay and the zone EV
 * exemption). The rejection cases mutate the parsed fixture
 * tree, so they hold for any formatting of {@code parking/parking_tariffs_fixture.json}, a byte-for-byte copy of the
 * schema-5 model
 * scripts/export_parking_golden_cases.py writes (eqasim-bs tests/fixtures/parking), and of
 * {@code parking/parking_tariffs_fixture_schema1.json}, the schema-1 model of version 1, which the reader must keep
 * accepting. Only facts of the fixture tariff table are pinned, not the exporter's metadata (assumption texts, sources).
 */
public class ParkingTariffsTest {
	private static final String FIXTURE_RESOURCE = "/parking/parking_tariffs_fixture.json";
	private static final String SCHEMA_1_RESOURCE = ParkingCostCalculatorTest.SCHEMA_1_TARIFFS_RESOURCE;
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The zones of the schema-1 fixture of version 1; the schema-5 fixture holds them unchanged and fourteen more. */
	private static final Set<String> SCHEMA_1_ZONES = Set.of("fx_bs_ia", "fx_bs_ib", "fx_sz", "fx_wob", "fx_pe", "fx_res_a",
			"fx_campus", "fx_frac");

	@Rule
	public TemporaryFolder temporary = new TemporaryFolder();

	private static Path resourcePath(String resource) throws Exception {
		URL url = ParkingTariffsTest.class.getResource(resource);
		assertNotNull("test resource " + resource + " is missing", url);
		return Path.of(url.toURI());
	}

	private static Path fixturePath() throws Exception {
		return resourcePath(FIXTURE_RESOURCE);
	}

	private static ObjectNode fixtureTree() throws Exception {
		return (ObjectNode) MAPPER.readTree(fixturePath().toFile());
	}

	private static ObjectNode schemaOneTree() throws Exception {
		return (ObjectNode) MAPPER.readTree(resourcePath(SCHEMA_1_RESOURCE).toFile());
	}

	private static ObjectNode zone(ObjectNode root, String zoneId) {
		return (ObjectNode) root.get("zones").get(zoneId);
	}

	/**
	 * The schema-5 fixture downgraded to schema 4: the document key option_price_beta_u_per_eur, the zone key ev_free_minutes
	 * and the garage key max_stay_min that schema 5 adds are removed, and the version is 4.
	 */
	private static void asSchemaFour(ObjectNode root) {
		root.remove("option_price_beta_u_per_eur");
		for (var zone : root.get("zones")) {
			((ObjectNode) zone).remove("ev_free_minutes");
		}
		for (var garage : root.get("garages")) {
			((ObjectNode) garage).remove("max_stay_min");
		}
		root.put("schema_version", 4);
	}

	/** The schema-5 fixture downgraded to schema 3: schema 4 without the commuter decay, which schema 3 does not have. */
	private static void asSchemaThree(ObjectNode root) {
		asSchemaFour(root);
		root.remove("garage_decay_commute_m");
		root.put("schema_version", 3);
	}

	/** The zone of the schema-5 fixture as a model of an older schema reads it: ev_free_minutes is empty there. */
	private static ZoneTariff withoutEvExemption(ZoneTariff zone) {
		return new ZoneTariff(zone.zoneId(), zone.zoneType(), zone.hourlyRateCents(), zone.billingUnitMinutes(),
				zone.freeIfStayAtMostMinutes(), zone.firstPeriodMinutes(), zone.firstPeriodCents(), zone.dailyCapCents(),
				zone.maxStayMinutes(), zone.longStayProductCents(), zone.memberDayCents(), zone.guestDayCents(),
				zone.feeStart_s(), zone.feeEnd_s(), zone.residentExempt(), zone.garageHourlyRateCents(),
				zone.garageBillingUnitMinutes(), zone.garageFirstPeriodMinutes(), zone.garageFirstPeriodCents(),
				zone.garageDailyCapCents(), zone.garageFeeStart_s(), zone.garageFeeEnd_s(), zone.commuterDayCents(),
				zone.searchTimeMinutes(), zone.residentPermitsValid(), OptionalInt.empty());
	}

	/** Applies one mutation to a fresh fixture tree; the model must be rejected with every fragment in the message. */
	private static void assertRejected(Consumer<ObjectNode> mutation, String... fragments) throws Exception {
		assertRejected(fixtureTree(), mutation, fragments);
	}

	private static void assertRejected(ObjectNode root, Consumer<ObjectNode> mutation, String... fragments) {
		mutation.accept(root);
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ParkingTariffs.parse(root));
		for (String fragment : fragments) {
			assertTrue("'" + fragment + "' missing in: " + error.getMessage(), error.getMessage().contains(fragment));
		}
	}

	/** Applies one mutation to a fresh fixture tree; the model must still be accepted, and is returned. */
	private static ParkingTariffs assertAccepted(Consumer<ObjectNode> mutation) throws Exception {
		ObjectNode root = fixtureTree();
		mutation.accept(root);
		return ParkingTariffs.parse(root);
	}

	@Test
	public void readsTheFixtureTariffModel() throws Exception {
		ParkingTariffs tariffs = ParkingTariffs.read(fixturePath());
		assertEquals(5, tariffs.schemaVersion());
		assertEquals(Set.of("fx_bs_ia", "fx_bs_ib", "fx_sz", "fx_wob", "fx_pe", "fx_res_a", "fx_campus", "fx_frac",
				"fx_bs_ia_v2", "fx_bs_ib_v2", "fx_wob_v2", "fx_campus_v2", "fx_garage_window_v2", "fx_capped_street_v2",
				"fx_campus_tie_v2", "fx_res_garage_v2", "fx_bga_v2", "fx_res_nopermit_v2", "fx_ia_nolong_v3",
				"fx_ev_street_v3", "fx_ev_clip_v3", "fx_ev_expired_v3"), tariffs.zones());
		assertNotNull(tariffs.tariffSnapshotDate());
		assertEquals(ParkingCostCalculator.TERMINAL_STAY_RULE_UNTIL_FEE_END, tariffs.terminalStayRule());
		assertFalse(tariffs.assumptions().isEmpty());
		assertEquals(3, tariffs.sources().size());

		ZoneTariff salzgitter = tariffs.zone("fx_sz").orElseThrow();
		assertEquals("fx_sz", salzgitter.zoneId());
		assertEquals(ZoneType.STREET_PAID, salzgitter.zoneType());
		assertEquals(OptionalLong.of(100), salzgitter.hourlyRateCents());
		assertEquals(OptionalInt.of(6), salzgitter.billingUnitMinutes());
		assertEquals(OptionalInt.of(30), salzgitter.freeIfStayAtMostMinutes());
		assertEquals(OptionalInt.of(60), salzgitter.firstPeriodMinutes());
		assertEquals(OptionalLong.of(70), salzgitter.firstPeriodCents());
		assertEquals(OptionalLong.empty(), salzgitter.dailyCapCents());
		assertEquals(OptionalInt.empty(), salzgitter.maxStayMinutes());
		assertEquals(36000, salzgitter.feeStart_s());
		assertEquals(64800, salzgitter.feeEnd_s());
		assertFalse(salzgitter.residentExempt());
		assertFalse(salzgitter.hasGarage());
		assertTrue(salzgitter.residentPermitsValid());

		ZoneTariff residents = tariffs.zone("fx_res_a").orElseThrow();
		assertEquals(ZoneType.RESIDENT_ZONE, residents.zoneType());
		assertTrue(residents.residentExempt());
		assertEquals(OptionalLong.of(0), residents.hourlyRateCents());
		assertEquals(OptionalInt.of(60), residents.billingUnitMinutes());
		assertEquals(OptionalInt.of(120), residents.maxStayMinutes());
		assertEquals(OptionalLong.of(900), residents.longStayProductCents());

		ZoneTariff campus = tariffs.zone("fx_campus").orElseThrow();
		assertEquals(ZoneType.CAMPUS, campus.zoneType());
		assertEquals(OptionalLong.of(350), campus.memberDayCents());
		assertEquals(OptionalLong.of(900), campus.guestDayCents());
		assertEquals(OptionalLong.empty(), campus.hourlyRateCents());
		assertEquals(OptionalInt.empty(), campus.billingUnitMinutes());
		assertFalse(campus.residentPermitsValid());

		assertEquals(OptionalLong.of(600), tariffs.zone("fx_wob").orElseThrow().dailyCapCents());
		assertEquals(21600, tariffs.zone("fx_wob").orElseThrow().feeStart_s());
	}

	/**
	 * The schema-5 keys of the fixture (spec v3 A1, A2 and A5), field by field: the option price coefficient of the document
	 * (the magnitude of the mode choice cost coefficient), the zone EV exemption (fx_ev_street_v3 240 min, fx_ev_clip_v3 240
	 * min within a maximum stay of 150 min, fx_ev_expired_v3 none: the export switched an expired rule off), a street zone with
	 * a maximum stay and no long-stay product (fx_ia_nolong_v3) and the garage maximum stay (120, 90 and none).
	 */
	@Test
	public void readsTheSchemaFiveKeysOfTheFixture() throws Exception {
		ParkingTariffs tariffs = ParkingTariffs.read(fixturePath());
		assertEquals(0.310998, tariffs.optionPriceBetaPerEuro(), 0.0);
		assertEquals(OptionalInt.of(240), tariffs.zone("fx_ev_street_v3").orElseThrow().evFreeMinutes());
		assertEquals(OptionalInt.empty(), tariffs.zone("fx_ev_street_v3").orElseThrow().maxStayMinutes());
		assertTrue(tariffs.zone("fx_ev_street_v3").orElseThrow().hasEvExemption());
		assertEquals(OptionalInt.of(240), tariffs.zone("fx_ev_clip_v3").orElseThrow().evFreeMinutes());
		assertEquals(OptionalInt.of(150), tariffs.zone("fx_ev_clip_v3").orElseThrow().maxStayMinutes());
		assertFalse(tariffs.zone("fx_ev_expired_v3").orElseThrow().hasEvExemption());
		assertFalse(tariffs.zone("fx_bs_ib").orElseThrow().hasEvExemption());
		assertEquals(2L, tariffs.zonesWithEvExemption());
		// A street zone with a maximum stay and nothing to sell above it is valid (spec v3 A1, ruling R-A-6).
		ZoneTariff noLongStay = tariffs.zone("fx_ia_nolong_v3").orElseThrow();
		assertEquals(OptionalInt.of(180), noLongStay.maxStayMinutes());
		assertEquals(OptionalLong.empty(), noLongStay.longStayProductCents());
		assertFalse(noLongStay.hasGarage());
		assertEquals(OptionalInt.of(120), garageById(tariffs, "fx_g20_max120").maxStayMinutes());
		assertEquals(OptionalInt.of(90), garageById(tariffs, "fx_g28_max90").maxStayMinutes());
		assertEquals(OptionalInt.empty(), garageById(tariffs, "fx_g01_core").maxStayMinutes());
		assertEquals(3L, tariffs.garagesWithMaximumStay());
		String setting = tariffs.v3MechanicsSetting();
		assertTrue(setting, setting.startsWith("ACTIVE, schema 5: option_price_beta_u_per_eur 0.310998"));
		assertTrue(setting, setting.contains("2 zone(s) with ev_free_minutes"));
		assertTrue(setting, setting.contains("3 garage(s) with max_stay_min"));
	}

	/**
	 * A model of schema 4 or older has no option price coefficient, no zone EV exemption and no garage maximum stay: it reads
	 * as beta 0 (the price-independent weights of v2), ev_free_minutes empty and max_stay_min empty, and its startup line says
	 * that the v3 levers are inactive (spec v3 A1, A2 and A5: a model with every maximum null prices exactly as schema 4).
	 */
	@Test
	public void aSchemaFourModelReadsAsBetaZeroAndNoMaximumStayAndNoEvExemption() throws Exception {
		ParkingTariffs schemaFour = assertAccepted(ParkingTariffsTest::asSchemaFour);
		assertEquals(4, schemaFour.schemaVersion());
		assertEquals(0.0, schemaFour.optionPriceBetaPerEuro(), 0.0);
		assertEquals(0L, schemaFour.zonesWithEvExemption());
		assertEquals(0L, schemaFour.garagesWithMaximumStay());
		assertTrue(schemaFour.garages().stream().allMatch(garage -> garage.maxStayMinutes().isEmpty()));
		assertTrue(schemaFour.zones().stream().allMatch(id -> schemaFour.zone(id).orElseThrow().evFreeMinutes().isEmpty()));
		assertEquals(38, schemaFour.garages().size());
		assertTrue(schemaFour.garageOptions().isActive());
		String setting = schemaFour.v3MechanicsSetting();
		assertTrue(setting, setting.startsWith("INACTIVE, schema 4"));
		assertTrue(setting, setting.contains("prices as schema 4"));
		// The zones are those of the schema-5 fixture without their EV exemption.
		ParkingTariffs schemaFive = ParkingTariffs.read(fixturePath());
		for (String zoneId : schemaFive.zones()) {
			assertEquals(zoneId, withoutEvExemption(schemaFive.zone(zoneId).orElseThrow()), schemaFour.zone(zoneId).orElseThrow());
		}
		// Older schemas have no such key either.
		assertEquals(0.0, ParkingTariffs.read(resourcePath(SCHEMA_1_RESOURCE)).optionPriceBetaPerEuro(), 0.0);
		assertEquals(0.0, assertAccepted(ParkingTariffsTest::asSchemaThree).optionPriceBetaPerEuro(), 0.0);
	}

	/** The v3 line of the read log says ACTIVE for a schema-5 model and INACTIVE for an older one, so it is never silent. */
	@Test
	public void theReadLogSaysWhetherTheV3MechanicsAreActive() throws Exception {
		List<Event> events = ParkingLogCapture.captureChecked(ParkingTariffs.class, () -> ParkingTariffs.read(fixturePath()));
		List<String> info = ParkingLogCapture.messages(events, Level.INFO);
		assertTrue(info.toString(), info.stream().anyMatch(line -> line.startsWith("[parking] v3 mechanics (spec v3 A1, A2 and"
				+ " A5): ACTIVE, schema 5")));
		ObjectNode schemaFour = fixtureTree();
		asSchemaFour(schemaFour);
		Path file = temporary.newFile("parking_tariffs_schema_4.json").toPath();
		MAPPER.writeValue(file.toFile(), schemaFour);
		List<String> oldInfo = ParkingLogCapture.messages(
				ParkingLogCapture.captureChecked(ParkingTariffs.class, () -> ParkingTariffs.read(file)), Level.INFO);
		assertTrue(oldInfo.toString(), oldInfo.stream().anyMatch(line -> line.contains("INACTIVE, schema 4 carries no"
				+ " option_price_beta_u_per_eur")));
	}

	/** The schema-5 keys are validated: a coefficient below 0 or a string, a non-positive or fractional minute count. */
	@Test
	public void rejectsInvalidSchemaFiveValues() throws Exception {
		assertRejected(root -> root.put("option_price_beta_u_per_eur", -0.1),
			"option_price_beta_u_per_eur must be a finite number >= 0");
		assertRejected(root -> root.put("option_price_beta_u_per_eur", "0.31"),
			"option_price_beta_u_per_eur must be a finite number");
		assertRejected(root -> root.putNull("option_price_beta_u_per_eur"), "option_price_beta_u_per_eur must be a finite number");
		assertRejected(root -> root.put("option_price_beta_u_per_eur", Double.POSITIVE_INFINITY),
				"option_price_beta_u_per_eur must be a finite number");
		// 0 is a valid coefficient of the reader (the price-independent weights); the startup check compares it with the mode
		// choice.
		assertEquals(0.0, assertAccepted(root -> root.put("option_price_beta_u_per_eur", 0.0)).optionPriceBetaPerEuro(), 0.0);
		assertRejected(root -> zone(root, "fx_ev_street_v3").put("ev_free_minutes", 0), "parking zone fx_ev_street_v3",
				"ev_free_minutes must be positive or null, got 0");
		assertRejected(root -> zone(root, "fx_ev_street_v3").put("ev_free_minutes", 90.5), "zone fx_ev_street_v3",
				"ev_free_minutes must be an integral number of minutes or null");
		assertRejected(root -> zone(root, "fx_ev_street_v3").put("ev_free_minutes", "240"), "zone fx_ev_street_v3",
				"ev_free_minutes must be an integral number of minutes or null");
		assertRejected(root -> garage(root, "fx_g20_max120").put("max_stay_min", 0), "garage fx_g20_max120",
				"max_stay_min must be at least 1");
		assertRejected(root -> garage(root, "fx_g20_max120").put("max_stay_min", 90.5), "garage fx_g20_max120",
				"max_stay_min must be an integral number of minutes or null");
		assertRejected(root -> garage(root, "fx_g20_max120").put("max_stay_min", "120"), "garage fx_g20_max120",
				"max_stay_min must be an integral number of minutes or null");
	}

	/** The schema-2 columns of the fixture rows, field by field: the garage product, the commuter product, the search time. */
	@Test
	public void readsTheSchemaTwoFieldsOfTheFixtureZones() throws Exception {
		ParkingTariffs tariffs = ParkingTariffs.read(fixturePath());

		ZoneTariff withFirstPeriod = tariffs.zone("fx_bs_ib_v2").orElseThrow();
		assertTrue(withFirstPeriod.hasGarage());
		assertEquals(OptionalLong.of(120), withFirstPeriod.garageHourlyRateCents());
		assertEquals(OptionalInt.of(60), withFirstPeriod.garageBillingUnitMinutes());
		assertEquals(OptionalInt.of(60), withFirstPeriod.garageFirstPeriodMinutes());
		assertEquals(OptionalLong.of(120), withFirstPeriod.garageFirstPeriodCents());
		assertEquals(OptionalLong.of(960), withFirstPeriod.garageDailyCapCents());
		assertEquals(OptionalInt.of(0), withFirstPeriod.garageFeeStart_s());
		assertEquals(OptionalInt.of(86400), withFirstPeriod.garageFeeEnd_s());
		assertEquals(OptionalLong.of(376), withFirstPeriod.commuterDayCents());
		assertEquals(OptionalInt.of(5), withFirstPeriod.searchTimeMinutes());
		assertTrue(withFirstPeriod.residentPermitsValid());

		// A garage without a day cap (spec amendment A6) and a garage window other than 0-24 h are valid.
		ZoneTariff uncappedWindow = tariffs.zone("fx_garage_window_v2").orElseThrow();
		assertEquals(OptionalLong.empty(), uncappedWindow.garageDailyCapCents());
		assertEquals(OptionalInt.of(36000), uncappedWindow.garageFeeStart_s());
		assertEquals(OptionalInt.of(64800), uncappedWindow.garageFeeEnd_s());
		assertEquals(OptionalLong.of(240), uncappedWindow.commuterDayCents());
		assertEquals(OptionalInt.empty(), uncappedWindow.searchTimeMinutes());

		// A maximum stay without a long-stay product is valid with a garage, also in a resident zone.
		ZoneTariff streetWithMaximumStay = tariffs.zone("fx_bs_ia_v2").orElseThrow();
		assertEquals(OptionalInt.of(180), streetWithMaximumStay.maxStayMinutes());
		assertEquals(OptionalLong.empty(), streetWithMaximumStay.longStayProductCents());
		ZoneTariff residentWithGarage = tariffs.zone("fx_res_garage_v2").orElseThrow();
		assertEquals(ZoneType.RESIDENT_ZONE, residentWithGarage.zoneType());
		assertEquals(OptionalLong.empty(), residentWithGarage.longStayProductCents());
		assertTrue(residentWithGarage.hasGarage());

		// A campus row may carry a commuter product (spec amendment A4) and a search time of 0.
		ZoneTariff campus = tariffs.zone("fx_campus_v2").orElseThrow();
		assertEquals(OptionalLong.of(190), campus.commuterDayCents());
		assertEquals(OptionalInt.of(0), campus.searchTimeMinutes());
		assertFalse(campus.hasGarage());

		// Rule R2-a: the separately operated car parks and the campus honour no resident permit.
		assertFalse(tariffs.zone("fx_bga_v2").orElseThrow().residentPermitsValid());
		assertFalse(tariffs.zone("fx_res_nopermit_v2").orElseThrow().residentPermitsValid());
		assertTrue(tariffs.zone("fx_res_nopermit_v2").orElseThrow().residentExempt());
	}

	/** The districts a plan attribute may name (spec Amendment C3): the id, its municipality, sorted as written. */
	@Test
	public void readsTheResidentDistrictsOfTheFixture() throws Exception {
		ParkingTariffs tariffs = ParkingTariffs.read(fixturePath());
		assertEquals(List.of(new ResidentDistrict("fx_district_a", "03101000"), new ResidentDistrict("fx_district_b", "03101000"),
				new ResidentDistrict("fx_district_sz_a", "03102000")), tariffs.residentDistricts());
		assertEquals("03102000", tariffs.residentDistrict("fx_district_sz_a").orElseThrow().municipalityAgs());
		assertTrue(tariffs.residentDistrict("fx_district_unknown").isEmpty());
		assertThrows(UnsupportedOperationException.class, () -> tariffs.residentDistricts().clear());

		// A model built without a district layer lists none.
		assertTrue(assertAccepted(root -> root.putArray("resident_districts")).residentDistricts().isEmpty());
	}

	/** Both fixtures describe the same zones; a drift between them would make one of the test classes moot. */
	@Test
	public void fixtureZonesEqualTheGoldenCaseTariffs() throws Exception {
		ParkingTariffs tariffs = ParkingTariffs.read(fixturePath());
		Map<String, ZoneTariff> golden = ParkingCostCalculatorTest.goldenTariffs();
		assertEquals(golden.keySet(), tariffs.zones());
		for (String zoneId : tariffs.zones()) {
			assertEquals(zoneId, golden.get(zoneId), tariffs.zone(zoneId).orElseThrow());
		}
	}

	/**
	 * Schema 2 is additive: the committed schema-1 model of version 1 still loads, its zones are the schema-2 zones of
	 * the same ids with the schema-2 fields empty and the permit flag of the zone type (spec Amendment C3, ASSUMPTION
	 * R2-a), and it lists no resident district.
	 */
	@Test
	public void readsTheSchemaOneModelAsTheSameZonesWithoutTheSchemaTwoFields() throws Exception {
		ParkingTariffs schemaOne = ParkingTariffs.read(resourcePath(SCHEMA_1_RESOURCE));
		assertEquals(1, schemaOne.schemaVersion());
		assertEquals(SCHEMA_1_ZONES, schemaOne.zones());
		assertTrue(schemaOne.residentDistricts().isEmpty());
		Map<String, ZoneTariff> golden = ParkingCostCalculatorTest.goldenTariffs();
		for (String zoneId : schemaOne.zones()) {
			ZoneTariff zone = schemaOne.zone(zoneId).orElseThrow();
			assertEquals(zoneId, golden.get(zoneId), zone);
			assertFalse(zoneId, zone.hasGarage());
			assertEquals(zoneId, OptionalLong.empty(), zone.commuterDayCents());
			assertEquals(zoneId, OptionalInt.empty(), zone.searchTimeMinutes());
			assertEquals(zoneId, zone.zoneType() != ZoneType.CAMPUS, zone.residentPermitsValid());
		}
	}

	@Test
	public void zoneIdsAreSortedAndImmutableAndAnUnknownIdIsEmpty() throws Exception {
		ParkingTariffs tariffs = ParkingTariffs.read(fixturePath());
		assertTrue(tariffs.zone("bs_zone_unknown").isEmpty());
		assertEquals(List.of("fx_bga_v2", "fx_bs_ia", "fx_bs_ia_v2", "fx_bs_ib", "fx_bs_ib_v2", "fx_campus", "fx_campus_tie_v2",
				"fx_campus_v2", "fx_capped_street_v2", "fx_ev_clip_v3", "fx_ev_expired_v3", "fx_ev_street_v3", "fx_frac",
				"fx_garage_window_v2", "fx_ia_nolong_v3", "fx_pe", "fx_res_a", "fx_res_garage_v2", "fx_res_nopermit_v2", "fx_sz",
				"fx_wob", "fx_wob_v2"), List.copyOf(tariffs.zones()));
		assertThrows(UnsupportedOperationException.class, () -> tariffs.zones().add("extra"));
		assertThrows(UnsupportedOperationException.class, () -> tariffs.zones().remove("fx_sz"));
		assertThrows(UnsupportedOperationException.class, () -> tariffs.assumptions().add("ASSUMPTION: extra"));
		assertThrows(UnsupportedOperationException.class, () -> tariffs.sources().clear());
	}

	/**
	 * Schema 1 to 5 are accepted; any other version, and any non-integral one, fails with the version AND the newest version the
	 * reader supports (both numbers named).
	 */
	@Test
	public void rejectsAnotherSchemaVersion() throws Exception {
		assertRejected(root -> root.put("schema_version", 6), "schema_version must be one of [1, 2, 3, 4, 5], got 6",
				"this reader supports schema 1 to 5");
		assertRejected(root -> root.put("schema_version", 0), "schema_version must be one of [1, 2, 3, 4, 5], got 0");
		assertRejected(root -> root.put("schema_version", -1), "schema_version must be one of [1, 2, 3, 4, 5], got -1");
		assertRejected(root -> root.put("schema_version", 1.5), "schema_version", "integral");
		assertRejected(root -> root.put("schema_version", "2"), "schema_version", "integral");
		assertRejected(root -> root.putNull("schema_version"), "schema_version", "integral");
	}

	/**
	 * The version decides which fields a document has, so it is checked before them: a document of another schema fails
	 * with its version, not with the fields that schema renamed or added.
	 */
	@Test
	public void checksTheSchemaVersionBeforeTheDocumentFields() throws Exception {
		ObjectNode schema6 = fixtureTree();
		schema6.put("schema_version", 6);
		schema6.remove("weekday_only");
		schema6.remove("resident_districts");
		schema6.put("service_days", "weekday");
		zone(schema6, "fx_sz").remove("garage_daily_cap_cents");
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ParkingTariffs.parse(schema6));
		assertEquals("parking_tariffs: schema_version must be one of [1, 2, 3, 4, 5], got 6 (this reader supports schema 1 to 5)",
				error.getMessage());

		ObjectNode unversioned = fixtureTree();
		unversioned.remove("schema_version");
		error = assertThrows(IllegalArgumentException.class, () -> ParkingTariffs.parse(unversioned));
		assertTrue(error.getMessage(), error.getMessage().startsWith("parking_tariffs: schema_version must be"));
	}

	/** After the version, the key set of THAT version is checked: schema 1 has no district list and no schema-2 zone keys. */
	@Test
	public void checksTheKeySetOfTheVersionTheDocumentDeclares() throws Exception {
		// A schema-5 document declaring schema 1 has the keys of schemas 2 to 5 too many.
		assertRejected(root -> root.put("schema_version", 1), "must have exactly the schema-1 fields",
				"unknown [garage_decay_commute_m, garage_decay_m, garage_max_distance_m, garages, option_price_beta_u_per_eur,"
						+ " resident_districts]");
		// A schema-5 document declaring schema 2 has the garage keys and the coefficient too many.
		assertRejected(root -> root.put("schema_version", 2), "must have exactly the schema-2 fields",
				"unknown [garage_decay_commute_m, garage_decay_m, garage_max_distance_m, garages, option_price_beta_u_per_eur]");
		// A schema-5 document declaring schema 3 has the commuter decay (a schema-3 file has none, spec H4) and the coefficient
		// too many.
		assertRejected(root -> root.put("schema_version", 3), "must have exactly the schema-3 fields",
				"unknown [garage_decay_commute_m, option_price_beta_u_per_eur]");
		// A schema-5 document declaring schema 4 has the coefficient too many.
		assertRejected(root -> root.put("schema_version", 4), "must have exactly the schema-4 fields",
				"unknown [option_price_beta_u_per_eur]");
		// A schema-4 document declaring schema 5 lacks the coefficient, which is never defaulted.
		assertRejected(root -> {
			asSchemaFour(root);
			root.put("schema_version", 5);
		}, "must have exactly the schema-5 fields", "missing [option_price_beta_u_per_eur]");
		// A schema-2 document (the schema-5 fixture without the garage keys) declaring schema 3 lacks them.
		assertRejected(root -> {
			removeGarageKeys(root);
			root.put("schema_version", 3);
		}, "must have exactly the schema-3 fields", "missing [garage_decay_m, garage_max_distance_m, garages]");
		// A schema-3 document declaring schema 4 lacks the commuter decay, which is never defaulted.
		assertRejected(root -> {
			asSchemaThree(root);
			root.put("schema_version", 4);
		}, "must have exactly the schema-4 fields", "missing [garage_decay_commute_m]");
		// A schema-1 document declaring schema 2 lacks the district list.
		ObjectNode schemaOne = schemaOneTree();
		assertRejected(schemaOne, root -> root.put("schema_version", 2), "must have exactly the schema-2 fields",
				"missing [resident_districts]");
		// A schema-1 zone has none of the ten schema-2 keys; a zone of schema 2 to 4 has all of them, a zone of schema 5 one more.
		assertRejected(schemaOneTree(), root -> zone(root, "fx_sz").put("search_time_min", 5), "zone fx_sz",
				"must have exactly the schema-1 fields", "unknown [search_time_min]");
		assertRejected(root -> zone(root, "fx_sz").remove("search_time_min"), "zone fx_sz",
				"must have exactly the schema-5 fields", "missing [search_time_min]");
		assertRejected(root -> zone(root, "fx_sz").remove("ev_free_minutes"), "zone fx_sz",
				"must have exactly the schema-5 fields", "missing [ev_free_minutes]");
		assertRejected(root -> {
			asSchemaFour(root);
			zone(root, "fx_sz").put("ev_free_minutes", 60);
		}, "zone fx_sz", "must have exactly the schema-4 fields", "unknown [ev_free_minutes]");
		assertRejected(root -> zone(root, "fx_sz").remove("resident_permits_valid"), "zone fx_sz",
				"missing [resident_permits_valid]");
		assertRejected(root -> root.remove("resident_districts"), "missing [resident_districts]");
	}

	@Test
	public void rejectsAnUnknownZoneType() throws Exception {
		assertRejected(root -> zone(root, "fx_pe").put("zone_type", "garage"), "zone fx_pe", "unknown zone_type garage",
				"street_paid");
		// JSON names are exact: a differently spelled known type is unknown, not guessed.
		assertRejected(root -> zone(root, "fx_pe").put("zone_type", "STREET_PAID"), "zone fx_pe",
				"unknown zone_type STREET_PAID");
	}

	/**
	 * A stay above the maximum stay loses the street product (spec lever 2). Spec v3 A1, ruling R-A-6 (a deliberate change of
	 * the v2 rule): a STREET zone may state a maximum stay without a long-stay product and without a garage core, with or
	 * without a commuter product; its stays above the maximum are served by the garage options or priced NO_FEASIBLE_OPTION. A
	 * RESIDENT zone keeps the v2 rule: its maximum stay needs a long-stay product or the garage core, and the commuter product
	 * alone does not suffice, because it serves work and education only (and a resident zone has none).
	 */
	@Test
	public void aStreetZoneMayStateAMaximumStayWithoutAnyProductAboveItButAResidentZoneMayNot() throws Exception {
		String message = "max_stay_min is set but neither long_stay_product_cents nor the garage core is";
		for (String streetZone : List.of("fx_bs_ia", "fx_pe")) {
			ParkingTariffs accepted = assertAccepted(root -> zone(root, streetZone).putNull("long_stay_product_cents"));
			ZoneTariff zone = accepted.zone(streetZone).orElseThrow();
			assertTrue(streetZone, zone.maxStayMinutes().isPresent());
			assertTrue(streetZone, zone.longStayProductCents().isEmpty());
		}
		assertRejected(root -> zone(root, "fx_res_a").putNull("long_stay_product_cents"), "parking zone fx_res_a", message);
		// A street zone with a maximum stay, no garage core and a commuter product is valid too.
		ObjectNode root = fixtureTree();
		for (String field : List.of("garage_hourly_rate_cents", "garage_billing_unit_min", "garage_fee_start_s", "garage_fee_end_s",
				"garage_daily_cap_cents")) {
			zone(root, "fx_bs_ia_v2").putNull(field);
		}
		zone(root, "fx_bs_ia_v2").put("commuter_day_cents", 376);
		ZoneTariff withCommuterOnly = ParkingTariffs.parse(root).zone("fx_bs_ia_v2").orElseThrow();
		assertEquals(OptionalLong.of(376), withCommuterOnly.commuterDayCents());
		// The resident zone with a garage core and no long-stay product stays valid; without the garage core it is not.
		ObjectNode residentRoot = fixtureTree();
		for (String field : List.of("garage_hourly_rate_cents", "garage_billing_unit_min", "garage_fee_start_s", "garage_fee_end_s",
				"garage_daily_cap_cents", "garage_first_period_min", "garage_first_period_cents")) {
			zone(residentRoot, "fx_res_garage_v2").putNull(field);
		}
		assertRejected(residentRoot, ignored -> {
		}, "parking zone fx_res_garage_v2", message);
	}

	/** A long-stay product is sold only above a maximum stay, so one without a maximum stay would never be sold. */
	@Test
	public void rejectsALongStayProductWithoutAMaximumStay() throws Exception {
		String message = "long_stay_product_cents needs max_stay_min";
		assertRejected(root -> zone(root, "fx_bs_ib").put("long_stay_product_cents", 900), "parking zone fx_bs_ib", message);
		assertRejected(root -> zone(root, "fx_bs_ia").putNull("max_stay_min"), "parking zone fx_bs_ia", message);
		// A garage does not change that.
		assertRejected(root -> zone(root, "fx_bs_ib_v2").put("long_stay_product_cents", 900), "parking zone fx_bs_ib_v2", message);
	}

	/**
	 * Fields a zone type does not have must be null (design section 3.1; the Python reference's _NOT_APPLICABLE_FIELDS,
	 * transcribed here field by field): the cost rules of the type never read them, so a value there would be a tariff
	 * element that is silently ignored. Each field is set alone, to a value its range check accepts. A campus stay pays a
	 * day product, so every garage field is one it does not have; a resident zone has no commuter product.
	 */
	@Test
	public void rejectsFieldsThatDoNotApplyToTheZoneType() throws Exception {
		List<String> garageFields = List.of("garage_hourly_rate_cents", "garage_billing_unit_min", "garage_first_period_min",
				"garage_first_period_cents", "garage_daily_cap_cents", "garage_fee_start_s", "garage_fee_end_s");
		Map<String, List<String>> notApplicableByZone = Map.of( //
				"fx_bs_ib", List.of("member_day_cents", "guest_day_cents"), //
				"fx_res_a", List.of("free_if_stay_at_most_min", "first_period_min", "first_period_cents", "daily_cap_cents",
						"member_day_cents", "guest_day_cents", "commuter_day_cents", "ev_free_minutes"), //
				"fx_campus", List.of("hourly_rate_cents", "billing_unit_min", "free_if_stay_at_most_min", "first_period_min",
						"first_period_cents", "daily_cap_cents", "max_stay_min", "long_stay_product_cents", "ev_free_minutes"));
		Map<String, String> zoneTypes = Map.of("fx_bs_ib", "street_paid", "fx_res_a", "resident_zone", "fx_campus", "campus");
		int rejected = 0;
		for (Map.Entry<String, List<String>> entry : notApplicableByZone.entrySet()) {
			String zoneId = entry.getKey();
			for (String field : entry.getValue()) {
				assertRejected(root -> zone(root, zoneId).put(field, 1), "parking zone " + zoneId,
						field + " does not apply to a " + zoneTypes.get(zoneId) + " zone and must be null, got 1");
				rejected++;
			}
		}
		for (String field : garageFields) {
			assertRejected(root -> zone(root, "fx_campus").put(field, 1), "parking zone fx_campus",
					field + " does not apply to a campus zone and must be null, got 1");
			rejected++;
		}
		assertEquals(2 + 8 + 9 + 7, rejected);
	}

	/**
	 * A daily cap below the first-period price contradicts the tariff: every metered stay buys the first period, and the
	 * cap would then price the stay below the block it bought (a rule eqasim-bs issue #436 adds to both implementations),
	 * for the street columns and for the garage columns alike. A cap equal to the first-period price is consistent.
	 */
	@Test
	public void rejectsADailyCapBelowTheFirstPeriodPrice() throws Exception {
		assertRejected(root -> zone(root, "fx_wob").put("daily_cap_cents", 109), "parking zone fx_wob",
				"daily_cap_cents 109 is below first_period_cents 110");
		assertRejected(root -> zone(root, "fx_sz").put("daily_cap_cents", 69), "parking zone fx_sz",
				"daily_cap_cents 69 is below first_period_cents 70");
		assertRejected(root -> zone(root, "fx_bs_ib_v2").put("garage_daily_cap_cents", 119), "parking zone fx_bs_ib_v2",
				"garage_daily_cap_cents 119 is below garage_first_period_cents 120");

		ObjectNode root = fixtureTree();
		zone(root, "fx_wob").put("daily_cap_cents", 110);
		zone(root, "fx_bs_ib_v2").put("garage_daily_cap_cents", 120);
		ParkingTariffs tariffs = ParkingTariffs.parse(root);
		assertEquals(OptionalLong.of(110), tariffs.zone("fx_wob").orElseThrow().dailyCapCents());
		assertEquals(OptionalLong.of(120), tariffs.zone("fx_bs_ib_v2").orElseThrow().garageDailyCapCents());
	}

	@Test
	public void rejectsNegativeCents() throws Exception {
		assertRejected(root -> zone(root, "fx_bs_ia").put("hourly_rate_cents", -180), "parking zone fx_bs_ia",
				"hourly_rate_cents must be non-negative");
		assertRejected(root -> zone(root, "fx_bs_ia").put("long_stay_product_cents", -900), "parking zone fx_bs_ia",
				"long_stay_product_cents must be non-negative");
		assertRejected(root -> zone(root, "fx_sz").put("first_period_cents", -70), "parking zone fx_sz",
				"first_period_cents must be non-negative");
		assertRejected(root -> zone(root, "fx_campus").put("member_day_cents", -350), "parking zone fx_campus",
				"member_day_cents must be non-negative");
		assertRejected(root -> zone(root, "fx_campus").put("guest_day_cents", -900), "parking zone fx_campus",
				"guest_day_cents must be non-negative");
		assertRejected(root -> zone(root, "fx_bs_ib").put("daily_cap_cents", -900), "parking zone fx_bs_ib",
				"daily_cap_cents must be positive");
		assertRejected(root -> zone(root, "fx_bs_ib_v2").put("garage_hourly_rate_cents", -120), "parking zone fx_bs_ib_v2",
				"garage_hourly_rate_cents must be non-negative");
		assertRejected(root -> zone(root, "fx_bs_ib_v2").put("garage_first_period_cents", -120), "parking zone fx_bs_ib_v2",
				"garage_first_period_cents must be non-negative");
		assertRejected(root -> zone(root, "fx_bs_ib_v2").put("commuter_day_cents", -376), "parking zone fx_bs_ib_v2",
				"commuter_day_cents must be non-negative");
		assertRejected(root -> zone(root, "fx_campus_v2").put("commuter_day_cents", -190), "parking zone fx_campus_v2",
				"commuter_day_cents must be non-negative");
		assertRejected(root -> zone(root, "fx_bs_ib_v2").put("search_time_min", -1), "parking zone fx_bs_ib_v2",
				"search_time_min must be non-negative");
	}

	/** Required fields per zone type (design section 3.1). */
	@Test
	public void rejectsMissingRequiredFieldsPerZoneType() throws Exception {
		assertRejected(root -> zone(root, "fx_bs_ib").putNull("hourly_rate_cents"), "parking zone fx_bs_ib",
				"hourly_rate_cents is required for a street_paid");
		assertRejected(root -> zone(root, "fx_bs_ib").putNull("billing_unit_min"), "parking zone fx_bs_ib",
				"billing_unit_min is required for a street_paid");
		assertRejected(root -> zone(root, "fx_res_a").putNull("max_stay_min"), "parking zone fx_res_a",
				"max_stay_min is required for a resident_zone");
		assertRejected(root -> zone(root, "fx_res_a").put("resident_exempt", false), "parking zone fx_res_a",
				"resident_exempt must be true for a resident_zone");
		assertRejected(root -> zone(root, "fx_res_a").put("hourly_rate_cents", 50), "parking zone fx_res_a",
				"hourly_rate_cents must be 0 for a resident_zone");
		assertRejected(root -> zone(root, "fx_res_a").putNull("hourly_rate_cents"), "parking zone fx_res_a",
				"hourly_rate_cents is required for a resident_zone");
		assertRejected(root -> zone(root, "fx_campus").putNull("member_day_cents"), "parking zone fx_campus",
				"member_day_cents is required for a campus");
		assertRejected(root -> zone(root, "fx_campus").putNull("guest_day_cents"), "parking zone fx_campus",
				"guest_day_cents is required for a campus");
	}

	/**
	 * Design 3.1 does not list a billing unit for a resident zone, but the metered step of section 3.2 divides by it for
	 * every non-campus zone; the Python reference braunschweig.parking.cost requires it, and so does this reader.
	 */
	@Test
	public void rejectsAResidentZoneWithoutBillingUnit() throws Exception {
		assertRejected(root -> zone(root, "fx_res_a").putNull("billing_unit_min"), "parking zone fx_res_a",
				"billing_unit_min is required for a resident_zone");
	}

	@Test
	public void rejectsInvalidFeeWindowsAndUnpairedFirstPeriods() throws Exception {
		assertRejected(root -> zone(root, "fx_bs_ib").put("fee_start_s", 75600), "parking zone fx_bs_ib", "fee window");
		assertRejected(root -> zone(root, "fx_bs_ib").put("fee_start_s", 72000), "parking zone fx_bs_ib", "fee window");
		assertRejected(root -> zone(root, "fx_bs_ib").put("fee_end_s", 86401), "parking zone fx_bs_ib", "fee window");
		assertRejected(root -> zone(root, "fx_bs_ib").put("fee_start_s", -1), "parking zone fx_bs_ib", "fee window");
		assertRejected(root -> zone(root, "fx_sz").putNull("first_period_cents"), "parking zone fx_sz",
				"first_period_min and first_period_cents must be set together");
		assertRejected(root -> zone(root, "fx_sz").putNull("first_period_min"), "parking zone fx_sz",
				"first_period_min and first_period_cents must be set together");
	}

	/**
	 * The garage product (spec lever 2, amendment A6): its core of hourly rate, billing unit and own fee window is given
	 * completely or not at all; the first-period pair is optional as a pair; the day cap is optional; cap and first period
	 * need the core, because without it they would never be priced.
	 */
	@Test
	public void rejectsAnIncompleteGarageCore() throws Exception {
		String core = "the garage core [garage_hourly_rate_cents, garage_billing_unit_min, garage_fee_start_s,"
				+ " garage_fee_end_s] must be given completely or not at all";
		for (String field : List.of("garage_hourly_rate_cents", "garage_billing_unit_min", "garage_fee_start_s",
				"garage_fee_end_s")) {
			assertRejected(root -> zone(root, "fx_bs_ib_v2").putNull(field), "parking zone fx_bs_ib_v2", core,
					"missing [" + field + "]");
		}
		// A core given only by its window, or only by its rate, is as incomplete.
		assertRejected(root -> {
			zone(root, "fx_bs_ib").put("garage_fee_start_s", 0);
			zone(root, "fx_bs_ib").put("garage_fee_end_s", 86400);
		}, "parking zone fx_bs_ib", core, "missing [garage_hourly_rate_cents, garage_billing_unit_min]");
		assertRejected(root -> zone(root, "fx_bs_ib").put("garage_hourly_rate_cents", 120), "parking zone fx_bs_ib", core);
	}

	@Test
	public void rejectsAGarageCapOrFirstPeriodWithoutTheGarageCore() throws Exception {
		String needsCore = "need(s) the garage core";
		assertRejected(root -> zone(root, "fx_sz").put("garage_daily_cap_cents", 500), "parking zone fx_sz",
				"garage_daily_cap_cents", needsCore);
		assertRejected(root -> {
			zone(root, "fx_sz").put("garage_first_period_min", 60);
			zone(root, "fx_sz").put("garage_first_period_cents", 120);
		}, "parking zone fx_sz", "garage_first_period_min, garage_first_period_cents", needsCore);
	}

	@Test
	public void rejectsAnUnpairedGarageFirstPeriod() throws Exception {
		assertRejected(root -> zone(root, "fx_bs_ib_v2").putNull("garage_first_period_cents"), "parking zone fx_bs_ib_v2",
				"garage_first_period_min and garage_first_period_cents must be set together");
		assertRejected(root -> zone(root, "fx_bs_ib_v2").putNull("garage_first_period_min"), "parking zone fx_bs_ib_v2",
				"garage_first_period_min and garage_first_period_cents must be set together");
		assertRejected(root -> zone(root, "fx_bs_ia_v2").put("garage_first_period_min", 60), "parking zone fx_bs_ia_v2",
				"garage_first_period_min and garage_first_period_cents must be set together");
	}

	@Test
	public void rejectsAnInvalidGarageFeeWindow() throws Exception {
		String message = "garage fee window";
		assertRejected(root -> zone(root, "fx_bs_ib_v2").put("garage_fee_start_s", 86400), "parking zone fx_bs_ib_v2", message);
		assertRejected(root -> zone(root, "fx_garage_window_v2").put("garage_fee_start_s", 64800),
				"parking zone fx_garage_window_v2", message);
		assertRejected(root -> zone(root, "fx_bs_ib_v2").put("garage_fee_end_s", 86401), "parking zone fx_bs_ib_v2", message);
		assertRejected(root -> zone(root, "fx_bs_ib_v2").put("garage_fee_start_s", -1), "parking zone fx_bs_ib_v2", message);
		// The street and the garage window are independent: a garage window narrower than the street's is valid.
		assertAccepted(root -> {
			zone(root, "fx_bs_ib_v2").put("garage_fee_start_s", 36000);
			zone(root, "fx_bs_ib_v2").put("garage_fee_end_s", 43200);
		});
	}

	/** The garage reuses the metering primitives, so its divisor, first period and cap are positive for the same reasons. */
	@Test
	public void rejectsZeroWhereTheGarageMeteringDividesOrTestsForTruthiness() throws Exception {
		assertRejected(root -> zone(root, "fx_bs_ib_v2").put("garage_billing_unit_min", 0), "parking zone fx_bs_ib_v2",
				"garage_billing_unit_min must be positive");
		assertRejected(root -> zone(root, "fx_bs_ib_v2").put("garage_first_period_min", 0), "parking zone fx_bs_ib_v2",
				"garage_first_period_min must be positive");
		assertRejected(root -> zone(root, "fx_bs_ib_v2").put("garage_daily_cap_cents", 0), "parking zone fx_bs_ib_v2",
				"garage_daily_cap_cents must be positive");
		// A free garage, a zero commuter product and a search time of 0 are values, not absences.
		ParkingTariffs tariffs = assertAccepted(root -> {
			zone(root, "fx_bs_ib_v2").put("garage_hourly_rate_cents", 0);
			zone(root, "fx_bs_ib_v2").put("commuter_day_cents", 0);
			zone(root, "fx_bs_ib_v2").put("search_time_min", 0);
		});
		ZoneTariff free = tariffs.zone("fx_bs_ib_v2").orElseThrow();
		assertEquals(OptionalLong.of(0), free.garageHourlyRateCents());
		assertEquals(OptionalLong.of(0), free.commuterDayCents());
		assertEquals(OptionalInt.of(0), free.searchTimeMinutes());
	}

	/** The commuter product exists on street_paid and campus rows only (spec amendment A4), never in a resident zone. */
	@Test
	public void rejectsACommuterProductInAResidentZone() throws Exception {
		assertRejected(root -> zone(root, "fx_res_a").put("commuter_day_cents", 190), "parking zone fx_res_a",
				"commuter_day_cents does not apply to a resident_zone zone and must be null, got 190");
		assertRejected(root -> zone(root, "fx_res_garage_v2").put("commuter_day_cents", 190), "parking zone fx_res_garage_v2",
				"commuter_day_cents does not apply to a resident_zone zone");
		assertAccepted(root -> zone(root, "fx_bga_v2").put("commuter_day_cents", 190));
		assertAccepted(root -> zone(root, "fx_campus").put("commuter_day_cents", 190));
	}

	/**
	 * Rule R2-a: a campus car park honours no resident permit, so its flag may not be true; the flag is a literal JSON
	 * boolean in every row, never null, because the exporter resolves the default of the zone type.
	 */
	@Test
	public void rejectsAPermitFlagThatIsTrueOnACampusOrNotABoolean() throws Exception {
		assertRejected(root -> zone(root, "fx_campus").put("resident_permits_valid", true), "parking zone fx_campus",
				"resident_permits_valid must be false for a campus zone");
		assertRejected(root -> zone(root, "fx_campus_v2").put("resident_permits_valid", true), "parking zone fx_campus_v2",
				"resident_permits_valid must be false for a campus zone");
		assertRejected(root -> zone(root, "fx_sz").putNull("resident_permits_valid"), "zone fx_sz", "resident_permits_valid",
				"boolean");
		assertRejected(root -> zone(root, "fx_sz").put("resident_permits_valid", "true"), "zone fx_sz", "resident_permits_valid",
				"boolean");
		assertRejected(root -> zone(root, "fx_sz").put("resident_permits_valid", 1), "zone fx_sz", "resident_permits_valid",
				"boolean");
		// False is valid everywhere, true on every row that is not a campus.
		ParkingTariffs tariffs = assertAccepted(root -> {
			zone(root, "fx_sz").put("resident_permits_valid", false);
			zone(root, "fx_res_a").put("resident_permits_valid", false);
		});
		assertFalse(tariffs.zone("fx_sz").orElseThrow().residentPermitsValid());
		assertFalse(tariffs.zone("fx_res_a").orElseThrow().residentPermitsValid());
	}

	/**
	 * The pseudo-code of section 3.2 tests these fields for truthiness ({@code if t.daily_cap_cents: ...}), where 0 reads
	 * as "absent" but an optional holding 0 is present; the reader rejects 0 instead of letting two ports disagree.
	 */
	@Test
	public void rejectsZeroWhereTheDesignTestsForTruthiness() throws Exception {
		assertRejected(root -> zone(root, "fx_bs_ib").put("daily_cap_cents", 0), "parking zone fx_bs_ib",
				"daily_cap_cents must be positive");
		assertRejected(root -> zone(root, "fx_bs_ia").put("max_stay_min", 0), "parking zone fx_bs_ia",
				"max_stay_min must be positive");
		assertRejected(root -> zone(root, "fx_sz").put("free_if_stay_at_most_min", 0), "parking zone fx_sz",
				"free_if_stay_at_most_min must be positive");
		assertRejected(root -> zone(root, "fx_sz").put("first_period_min", 0), "parking zone fx_sz",
				"first_period_min must be positive");
		assertRejected(root -> zone(root, "fx_bs_ia").put("billing_unit_min", 0), "parking zone fx_bs_ia",
				"billing_unit_min must be positive");
	}

	/** Money, minutes and seconds are integral JSON numbers: no fraction is truncated, no string is parsed. */
	@Test
	public void rejectsNonIntegralOrMistypedValues() throws Exception {
		assertRejected(root -> zone(root, "fx_bs_ia").put("hourly_rate_cents", 180.5), "zone fx_bs_ia",
				"hourly_rate_cents", "integral");
		// An integral-valued float is still a float: money never travels as a floating-point number.
		assertRejected(root -> zone(root, "fx_bs_ia").put("hourly_rate_cents", 180.0), "zone fx_bs_ia",
				"hourly_rate_cents", "integral");
		assertRejected(root -> zone(root, "fx_bs_ia").put("hourly_rate_cents", "180"), "zone fx_bs_ia",
				"hourly_rate_cents", "integral");
		assertRejected(root -> zone(root, "fx_bs_ia").put("billing_unit_min", 1.5), "zone fx_bs_ia", "billing_unit_min",
				"integral");
		assertRejected(root -> zone(root, "fx_bs_ia").put("fee_start_s", 32400.5), "zone fx_bs_ia", "fee_start_s",
				"integral");
		assertRejected(root -> zone(root, "fx_bs_ia").putNull("fee_end_s"), "zone fx_bs_ia", "fee_end_s", "integral");
		assertRejected(root -> zone(root, "fx_bs_ia").put("resident_exempt", "false"), "zone fx_bs_ia", "resident_exempt",
				"boolean");
		assertRejected(root -> zone(root, "fx_bs_ia").putNull("zone_type"), "zone fx_bs_ia", "zone_type");
		// The schema-2 fields are typed the same way.
		assertRejected(root -> zone(root, "fx_bs_ib_v2").put("garage_hourly_rate_cents", 120.5), "zone fx_bs_ib_v2",
				"garage_hourly_rate_cents", "integral");
		assertRejected(root -> zone(root, "fx_bs_ib_v2").put("garage_billing_unit_min", "60"), "zone fx_bs_ib_v2",
				"garage_billing_unit_min", "integral");
		assertRejected(root -> zone(root, "fx_bs_ib_v2").put("garage_fee_end_s", 86400.5), "zone fx_bs_ib_v2",
				"garage_fee_end_s", "integral");
		assertRejected(root -> zone(root, "fx_bs_ib_v2").put("commuter_day_cents", 376.0), "zone fx_bs_ib_v2",
				"commuter_day_cents", "integral");
		assertRejected(root -> zone(root, "fx_bs_ib_v2").put("search_time_min", 2.5), "zone fx_bs_ib_v2", "search_time_min",
				"integral");
		assertRejected(root -> zone(root, "fx_bs_ib_v2").put("search_time_min", "5"), "zone fx_bs_ib_v2", "search_time_min",
				"integral");
	}

	/** Every object carries exactly its keys of the declared schema: a dropped or renamed field is not read as null. */
	@Test
	public void rejectsMissingOrUnknownKeys() throws Exception {
		assertRejected(root -> zone(root, "fx_bs_ia").remove("daily_cap_cents"), "zone fx_bs_ia",
				"missing [daily_cap_cents]");
		assertRejected(root -> zone(root, "fx_bs_ia").remove("garage_daily_cap_cents"), "zone fx_bs_ia",
				"missing [garage_daily_cap_cents]");
		assertRejected(root -> zone(root, "fx_bs_ia").put("workplace_class", "bs_zentrum"), "zone fx_bs_ia",
				"unknown [workplace_class]");
		assertRejected(root -> root.remove("currency"), "missing [currency]");
		assertRejected(root -> root.put("generated_by", "test"), "unknown [generated_by]");
		assertRejected(root -> ((ObjectNode) root.get("sources").get(0)).remove("sha256"), "sources entry",
				"missing [sha256]");
	}

	@Test
	public void rejectsContractValuesTheCalculatorDoesNotImplement() throws Exception {
		assertRejected(root -> root.put("currency", "CHF"), "currency must be EUR");
		assertRejected(root -> root.put("terminal_stay_rule", "eight_hours"), "terminal_stay_rule must be until_fee_end");
		assertRejected(root -> root.put("weekday_only", false), "weekday_only must be true");
		assertRejected(root -> root.put("weekday_only", "true"), "weekday_only must be true");
		assertRejected(root -> root.put("tariff_snapshot_date", "28.09.2026"), "tariff_snapshot_date must be an ISO date");
		assertRejected(root -> ((ObjectNode) root.get("sources").get(0)).put("sha256", "abc"),
				"sha256 must be 64 lowercase hex characters");
		assertRejected(root -> root.putObject("zones"), "zones must be a non-empty object");
		assertRejected(root -> root.putArray("assumptions"), "assumptions must be a non-empty array");
		assertRejected(root -> root.putArray("sources"), "sources must be a non-empty array");
	}

	/**
	 * The district list (spec Amendment C3) is an array of entries with exactly a district id and the AGS of its
	 * municipality, ids unique: a plan check that tests membership must not be fooled by a duplicate or a malformed entry.
	 */
	@Test
	public void rejectsAMalformedResidentDistrictList() throws Exception {
		assertRejected(root -> root.put("resident_districts", "fx_district_a"), "resident_districts must be an array");
		assertRejected(root -> root.putObject("resident_districts"), "resident_districts must be an array");
		assertRejected(root -> ((ArrayNode) root.get("resident_districts")).add("fx_district_x"),
				"resident_districts entry must be a JSON object");
		assertRejected(root -> ((ObjectNode) root.get("resident_districts").get(0)).remove("municipality_ags"),
				"resident_districts entry", "missing [municipality_ags]");
		assertRejected(root -> ((ObjectNode) root.get("resident_districts").get(0)).put("name", "A"),
				"resident_districts entry", "unknown [name]");
		assertRejected(root -> ((ObjectNode) root.get("resident_districts").get(1)).put("district_id", "fx_district_a"),
				"duplicate district_id fx_district_a");
		assertRejected(root -> ((ObjectNode) root.get("resident_districts").get(0)).put("district_id", " "),
				"resident_districts.district_id");
		assertRejected(root -> ((ObjectNode) root.get("resident_districts").get(0)).put("district_id", " fx_district_a"),
				"resident_districts.district_id", "surrounding whitespace");
		assertRejected(root -> ((ObjectNode) root.get("resident_districts").get(0)).put("district_id", 7),
				"resident_districts.district_id");
		for (Object ags : new Object[] { "3101000", "031010000", "0310100a", "", 3101000 }) {
			assertRejected(root -> {
				ObjectNode district = (ObjectNode) root.get("resident_districts").get(0);
				if (ags instanceof Integer number) {
					district.put("municipality_ags", number);
				} else {
					district.put("municipality_ags", (String) ags);
				}
			}, "resident_districts.municipality_ags", "8-digit");
		}
	}

	/** Jackson keeps the last of two equal keys by default, which would silently drop a tariff; the reader refuses. */
	@Test
	public void readRejectsDuplicateZoneIds() throws Exception {
		String campus = """
				{"zone_type": "campus", "hourly_rate_cents": null, "billing_unit_min": null, "free_if_stay_at_most_min": null,
				 "first_period_min": null, "first_period_cents": null, "daily_cap_cents": null, "max_stay_min": null,
				 "long_stay_product_cents": null, "member_day_cents": 350, "guest_day_cents": 900, "fee_start_s": 0,
				 "fee_end_s": 86400, "resident_exempt": false}""";
		String document = """
				{"schema_version": 1, "tariff_snapshot_date": "2026-09-28", "currency": "EUR",
				 "terminal_stay_rule": "until_fee_end", "weekday_only": true, "assumptions": ["ASSUMPTION: test document"],
				 "sources": [{"source_id": "fixture", "path": "fixture",
				              "sha256": "0000000000000000000000000000000000000000000000000000000000000000"}],
				 "zones": {"%s": %s, "%s": %s}}
				""";
		Path distinct = temporary.newFile("distinct_zone_ids.json").toPath();
		Files.writeString(distinct, document.formatted("campus_a", campus, "campus_b", campus), StandardCharsets.UTF_8);
		assertEquals(Set.of("campus_a", "campus_b"), ParkingTariffs.read(distinct).zones());

		Path duplicate = temporary.newFile("duplicate_zone_ids.json").toPath();
		Files.writeString(duplicate, document.formatted("campus_a", campus, "campus_a", campus), StandardCharsets.UTF_8);
		IOException error = assertThrows(IOException.class, () -> ParkingTariffs.read(duplicate));
		assertTrue(error.getMessage(), error.getMessage().contains("campus_a"));
	}

	/**
	 * Content after the document fails instead of being ignored: Jackson stops reading after the first value by default,
	 * so a second export appended to the file (a writer that appends instead of replacing) would go unnoticed.
	 */
	@Test
	public void readRejectsContentAfterTheDocument() throws Exception {
		String document = Files.readString(fixturePath(), StandardCharsets.UTF_8);
		Path appended = temporary.newFile("parking_tariffs_appended.json").toPath();
		Files.writeString(appended, document + System.lineSeparator() + document, StandardCharsets.UTF_8);
		IOException error = assertThrows(IOException.class, () -> ParkingTariffs.read(appended));
		assertTrue(error.getMessage(), error.getMessage().contains("FAIL_ON_TRAILING_TOKENS"));
	}

	@Test
	public void readNamesTheFileOfAnInvalidModel() throws Exception {
		ObjectNode root = fixtureTree();
		root.put("schema_version", 6);
		Path file = temporary.newFile("parking_tariffs_schema_6.json").toPath();
		MAPPER.writeValue(file.toFile(), root);
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ParkingTariffs.read(file));
		assertTrue(error.getMessage(), error.getMessage().contains("parking_tariffs_schema_6.json"));
		assertTrue(error.getMessage(), error.getMessage().contains("schema_version must be one of [1, 2, 3, 4, 5], got 6"));
	}

	/**
	 * Lever 4 can act only in a zone whose search_time_min is above 0: the model counts those zones and the read log line
	 * reports the number, so a model without search times is visible when it is loaded. The fixture has three
	 * (fx_bs_ia_v2 and fx_bs_ib_v2 5 min, fx_wob_v2 2 min); fx_campus_v2 holds an explicit 0 and does not count, and the
	 * schema-1 fixture has the column in none.
	 */
	@Test
	public void countsTheZonesWithASearchTimeAndReportsThemWhenReadingTheModel() throws Exception {
		ParkingTariffs[] read = new ParkingTariffs[1];
		List<Event> events = ParkingLogCapture.captureChecked(ParkingTariffs.class,
				() -> read[0] = ParkingTariffs.read(fixturePath()));
		ParkingTariffs tariffs = read[0];
		assertEquals(3L, tariffs.zonesWithSearchTime());
		assertTrue(tariffs.zone("fx_campus_v2").orElseThrow().searchTimeMinutes().isPresent());
		assertFalse(tariffs.zone("fx_campus_v2").orElseThrow().hasSearchTime());
		assertTrue(tariffs.zone("fx_wob_v2").orElseThrow().hasSearchTime());
		assertFalse(tariffs.zone("fx_sz").orElseThrow().hasSearchTime());
		List<String> info = ParkingLogCapture.messages(events, Level.INFO);
		// One line for the model, one for the garage options and one for the v3 mechanics.
		assertEquals(info.toString(), 3, info.size());
		assertTrue(info.get(0), info.get(0).contains("3 zone(s) with a search time"));
		assertTrue(info.get(1), info.get(1).contains("garage options"));
		assertTrue(info.get(2), info.get(2).contains("v3 mechanics"));

		ParkingTariffs[] readSchemaOne = new ParkingTariffs[1];
		List<Event> schemaOneEvents = ParkingLogCapture.captureChecked(ParkingTariffs.class,
				() -> readSchemaOne[0] = ParkingTariffs.read(resourcePath(SCHEMA_1_RESOURCE)));
		assertEquals(0L, readSchemaOne[0].zonesWithSearchTime());
		List<String> schemaOneInfo = ParkingLogCapture.messages(schemaOneEvents, Level.INFO);
		assertTrue(schemaOneInfo.toString(), schemaOneInfo.get(0).contains("0 zone(s) with a search time"));
	}

	/** Removes the garage keys and the schema-5 keys of the fixture: what a schema-2 document has not. */
	private static void removeGarageKeys(ObjectNode root) {
		root.remove("garages");
		root.remove("garage_decay_m");
		root.remove("garage_decay_commute_m");
		root.remove("garage_max_distance_m");
		root.remove("option_price_beta_u_per_eur");
		for (var zone : root.get("zones")) {
			((ObjectNode) zone).remove("ev_free_minutes");
		}
	}

	private static ObjectNode garage(ObjectNode root, String garageId) {
		for (var entry : root.get("garages")) {
			if (garageId.equals(entry.get("garage_id").asText())) {
				return (ObjectNode) entry;
			}
		}
		throw new AssertionError("fixture garage " + garageId + " is missing");
	}

	// ------------------------------------------------------------------------------------ schema 3 and 4: garages

	/**
	 * The 38 fixture garages, the two decay lengths lambda and lambda_c and D_max, field by field for one garage of every
	 * structure.
	 */
	@Test
	public void readsTheGaragesOfTheSchemaFiveFixture() throws Exception {
		ParkingTariffs tariffs = ParkingTariffs.read(fixturePath());
		assertEquals(5, tariffs.schemaVersion());
		assertEquals(400.0, tariffs.garageDecayMeters(), 0.0);
		assertEquals(250.0, tariffs.garageCommuteDecayMeters(), 0.0);
		assertEquals(250.0, tariffs.garageOptions().commuteDecayMeters(), 0.0);
		assertEquals(1000.0, tariffs.garageMaximumDistanceMeters(), 0.0);
		assertTrue(tariffs.garageOptions().isActive());
		assertEquals(38, tariffs.garages().size());
		List<String> ids = tariffs.garages().stream().map(GarageTariff::garageId).toList();
		assertEquals("garages are ordered by ascending id", ids.stream().sorted().toList(), ids);

		GarageTariff core = garageById(tariffs, "fx_g01_core");
		assertEquals(580300.0, core.x_m(), 0.0);
		assertEquals(5750000.0, core.y_m(), 0.0);
		assertEquals(OptionalLong.of(200), core.hourlyRateCents());
		assertEquals(OptionalInt.of(60), core.billingUnitMinutes());
		assertEquals(OptionalInt.of(0), core.feeStart_s());
		assertEquals(OptionalInt.of(86400), core.feeEnd_s());
		assertEquals(OptionalLong.of(6300), core.monthlyCents());
		assertFalse(core.hasTiers());
		assertFalse(core.hasBands());

		GarageTariff tiers = garageById(tariffs, "fx_g07_tiers");
		assertTrue(tiers.hasTiers());
		assertFalse(tiers.hasBands());
		assertEquals(4, tiers.tiers().size());
		assertEquals(new GarageTariff.Tier(82800, 28800, 30, 10), tiers.tiers().get(3));
		assertTrue("a tier may cross midnight", tiers.tiers().get(3).covers(3600));
		assertEquals(OptionalInt.empty(), tiers.feeStart_s());

		GarageTariff firstPeriodWindow = garageById(tariffs, "fx_g09_fp");
		assertEquals(OptionalInt.of(60), firstPeriodWindow.firstPeriodMinutes());
		assertEquals(OptionalLong.of(110), firstPeriodWindow.firstPeriodCents());
		assertEquals(OptionalInt.of(21600), firstPeriodWindow.firstPeriodStart_s());
		assertEquals(OptionalInt.of(86400), firstPeriodWindow.firstPeriodEnd_s());
		assertEquals(OptionalLong.of(600), firstPeriodWindow.dailyCapCents());

		GarageTariff bands = garageById(tariffs, "fx_g08_bands");
		assertTrue(bands.hasBands());
		assertFalse(bands.hasTiers());
		assertEquals(5, bands.bands().size());
		assertEquals(new GarageTariff.Band(120, OptionalInt.of(240), GarageTariff.BandKind.INCREMENT, 50, OptionalInt.of(60)),
				bands.bands().get(2));
		assertEquals(OptionalInt.empty(), bands.bands().get(4).toMinutes());
		assertEquals(OptionalLong.of(1500), bands.dailyCapCents());

		// Spec E14 amendment (1): a tiered garage with ONE closed free band as its grace period carries both lists.
		GarageTariff tierGrace = garageById(tariffs, "fx_g15_tier_grace");
		assertTrue(tierGrace.hasTiers());
		assertTrue(tierGrace.hasBands());
		assertEquals(1, tierGrace.bands().size());
		assertEquals(GarageTariff.BandKind.FREE, tierGrace.bands().get(0).kind());

		// The free schedule: one open free band with the formal fee window.
		GarageTariff free = garageById(tariffs, "fx_g17_free");
		assertEquals(1, free.bands().size());
		assertEquals(OptionalInt.empty(), free.bands().get(0).toMinutes());
		assertEquals(OptionalInt.of(86400), free.feeEnd_s());
	}

	private static GarageTariff garageById(ParkingTariffs tariffs, String garageId) {
		return tariffs.garages().stream().filter(garage -> garage.garageId().equals(garageId)).findFirst()
				.orElseThrow(() -> new AssertionError("fixture garage " + garageId + " is missing"));
	}

	/** Schema 2 is the schema-5 fixture without the garage keys: no garage, both decays 0, the garage options off. */
	@Test
	public void aSchemaTwoModelHasNoGarageOptionsAndPricesAsBefore() throws Exception {
		ObjectNode root = fixtureTree();
		removeGarageKeys(root);
		root.put("schema_version", 2);
		ParkingTariffs schemaTwo = ParkingTariffs.parse(root);
		assertEquals(2, schemaTwo.schemaVersion());
		assertTrue(schemaTwo.garages().isEmpty());
		assertEquals(0.0, schemaTwo.garageDecayMeters(), 0.0);
		assertEquals(0.0, schemaTwo.garageCommuteDecayMeters(), 0.0);
		assertFalse(schemaTwo.garageOptions().isActive());
		ParkingTariffs schemaFive = ParkingTariffs.read(fixturePath());
		assertEquals(schemaFive.zones(), schemaTwo.zones());
		for (String zoneId : schemaTwo.zones()) {
			// A schema-2 zone has no EV exemption; every other field is that of the schema-5 zone.
			assertEquals(zoneId, withoutEvExemption(schemaFive.zone(zoneId).orElseThrow()), schemaTwo.zone(zoneId).orElseThrow());
		}
		assertEquals(0.0, schemaTwo.optionPriceBetaPerEuro(), 0.0);
		ParkingTariffs schemaOne = ParkingTariffs.read(resourcePath(SCHEMA_1_RESOURCE));
		assertTrue(schemaOne.garages().isEmpty());
		assertEquals(0.0, schemaOne.garageCommuteDecayMeters(), 0.0);
		assertFalse(schemaOne.garageOptions().isActive());
	}

	/** Both decays 0 switch the garage options off (the sensitivity arm): garages listed or not, no option code runs. */
	@Test
	public void bothDecaysOfZeroSwitchTheGarageOptionsOff() throws Exception {
		ParkingTariffs withGarages = assertAccepted(root -> {
			root.put("garage_decay_m", 0.0);
			root.put("garage_decay_commute_m", 0.0);
		});
		assertEquals(38, withGarages.garages().size());
		assertFalse(withGarages.garageOptions().isActive());
		assertTrue(withGarages.garageOptionsSetting(), withGarages.garageOptionsSetting().startsWith("OFF"));
		assertTrue(withGarages.garageOptionsSetting(), withGarages.garageOptionsSetting().contains("garage_decay_m 0.0 m"));
		assertTrue(withGarages.garageOptionsSetting(),
				withGarages.garageOptionsSetting().contains("garage_decay_commute_m 0.0 m"));
		ParkingTariffs withoutGarages = assertAccepted(root -> {
			root.put("garage_decay_m", 0);
			root.put("garage_decay_commute_m", 0);
			root.putArray("garages");
		});
		assertTrue(withoutGarages.garages().isEmpty());
		assertFalse(withoutGarages.garageOptions().isActive());
	}

	/** Spec Amendment H1: the options act for a purpose iff its decay is above 0, so ONE decay above 0 keeps the model on. */
	@Test
	public void aModelIsOnWhenEitherDecayIsAboveZero() throws Exception {
		ParkingTariffs commuterOff = assertAccepted(root -> root.put("garage_decay_commute_m", 0.0));
		assertTrue(commuterOff.garageOptions().isActive());
		assertEquals(400.0, commuterOff.garageDecayMeters(), 0.0);
		assertEquals(0.0, commuterOff.garageCommuteDecayMeters(), 0.0);
		assertTrue(commuterOff.garageOptionsSetting(), commuterOff.garageOptionsSetting().startsWith("ON"));
		ParkingTariffs visitorsOff = assertAccepted(root -> root.put("garage_decay_m", 0.0));
		assertTrue(visitorsOff.garageOptions().isActive());
		assertEquals(0.0, visitorsOff.garageDecayMeters(), 0.0);
		assertEquals(250.0, visitorsOff.garageCommuteDecayMeters(), 0.0);
		assertTrue(visitorsOff.garageOptionsSetting(), visitorsOff.garageOptionsSetting().startsWith("ON"));
	}

	/** The startup line names both decay lengths, in the ON and in the OFF state. */
	@Test
	public void theGarageOptionsSettingNamesBothDecayLengths() throws Exception {
		String on = ParkingTariffs.read(fixturePath()).garageOptionsSetting();
		assertTrue(on, on.startsWith("ON, 38 garage(s)"));
		assertTrue(on, on.contains("garage_decay_m 400.0 m"));
		assertTrue(on, on.contains("garage_decay_commute_m 250.0 m"));
		assertTrue(on, on.contains("garage_max_distance_m 1000.0 m"));
		assertTrue(on, on.contains("schema 5"));
	}

	/**
	 * Spec Amendment H4: a schema-3 file has no commuter decay and prices as schema 3 always did, one decay length for every
	 * purpose: the reader sets the commuter decay to garage_decay_m, and the startup line says so.
	 */
	@Test
	public void aSchemaThreeModelPricesEveryPurposeWithTheDecayOfTheFile() throws Exception {
		ParkingTariffs schemaThree = assertAccepted(ParkingTariffsTest::asSchemaThree);
		assertEquals(3, schemaThree.schemaVersion());
		assertEquals(400.0, schemaThree.garageDecayMeters(), 0.0);
		assertEquals(400.0, schemaThree.garageCommuteDecayMeters(), 0.0);
		assertEquals(38, schemaThree.garages().size());
		assertTrue(schemaThree.garageOptions().isActive());
		String setting = schemaThree.garageOptionsSetting();
		assertTrue(setting, setting.contains("garage_decay_commute_m 400.0 m"));
		assertTrue(setting, setting.contains("schema 3"));
		assertTrue(setting, setting.contains("no commuter decay"));
		ParkingTariffs off = assertAccepted(root -> {
			asSchemaThree(root);
			root.put("garage_decay_m", 0.0);
		});
		assertFalse(off.garageOptions().isActive());
		assertEquals(0.0, off.garageCommuteDecayMeters(), 0.0);
		// The schema-3 zones are those of schema 5 (without their EV exemption).
		assertEquals(ParkingTariffs.read(fixturePath()).zones(), schemaThree.zones());
	}

	/** A schema-3 file with the schema-4 key, and a schema-4 file without it, are refused (exact key sets per schema). */
	@Test
	public void theCommuterDecayKeyIsExactlyTheKeyOfSchemaFour() throws Exception {
		assertRejected(root -> {
			asSchemaThree(root);
			root.put("garage_decay_commute_m", 250.0);
		}, "must have exactly the schema-3 fields", "unknown [garage_decay_commute_m]");
		assertRejected(root -> {
			asSchemaFour(root);
			root.remove("garage_decay_commute_m");
		}, "must have exactly the schema-4 fields", "missing [garage_decay_commute_m]");
	}

	@Test
	public void rejectsGarageOptionsThatAreOnWithoutAnyGarage() throws Exception {
		assertRejected(root -> root.putArray("garages"), "garage_decay_m is 400.0", "garage_decay_commute_m is 250.0",
				"lists no priced garage");
		// Either decay above 0 turns the options on, so either one is refused without a garage.
		assertRejected(root -> {
			root.put("garage_decay_m", 0.0);
			root.putArray("garages");
		}, "garage_decay_m is 0.0", "garage_decay_commute_m is 250.0", "lists no priced garage");
		assertRejected(root -> {
			root.put("garage_decay_commute_m", 0.0);
			root.putArray("garages");
		}, "garage_decay_m is 400.0", "garage_decay_commute_m is 0.0", "lists no priced garage");
	}

	@Test
	public void rejectsInvalidGarageParameters() throws Exception {
		assertRejected(root -> root.put("garage_decay_m", -1.0), "garage_decay_m must be a finite number of metres >= 0");
		assertRejected(root -> root.put("garage_decay_m", "400"), "garage_decay_m must be a finite number");
		assertRejected(root -> root.putNull("garage_decay_m"), "garage_decay_m must be a finite number");
		assertRejected(root -> root.put("garage_decay_commute_m", -1.0),
				"garage_decay_commute_m must be a finite number of metres >= 0");
		assertRejected(root -> root.put("garage_decay_commute_m", "250"), "garage_decay_commute_m must be a finite number");
		assertRejected(root -> root.putNull("garage_decay_commute_m"), "garage_decay_commute_m must be a finite number");
		assertRejected(root -> root.put("garage_decay_commute_m", Double.POSITIVE_INFINITY),
				"garage_decay_commute_m must be a finite number");
		assertRejected(root -> root.put("garage_max_distance_m", 0.0),
				"garage_max_distance_m must be a finite number of metres > 0");
		assertRejected(root -> root.put("garage_max_distance_m", -5),
				"garage_max_distance_m must be a finite number of metres > 0");
		assertRejected(root -> root.put("garage_max_distance_m", "1000"), "garage_max_distance_m must be a finite number");
		assertRejected(root -> root.put("garages", "none"), "garages must be an array of garages");
	}

	/** The garage entry has exactly the 16 keys of the Python reference; tiers 4 and bands 5. */
	@Test
	public void rejectsMissingOrUnknownGarageKeys() throws Exception {
		assertRejected(root -> garage(root, "fx_g01_core").remove("monthly_cents"), "garages entry fx_g01_core",
				"schema-5 fields", "missing [monthly_cents]");
		assertRejected(root -> garage(root, "fx_g01_core").remove("max_stay_min"), "garages entry fx_g01_core",
				"schema-5 fields", "missing [max_stay_min]");
		assertRejected(root -> {
			asSchemaFour(root);
			garage(root, "fx_g01_core").put("max_stay_min", 120);
		}, "garages entry fx_g01_core", "schema-4 fields", "unknown [max_stay_min]");
		assertRejected(root -> garage(root, "fx_g01_core").put("capacity", 100), "garages entry fx_g01_core",
				"unknown [capacity]");
		assertRejected(root -> ((ObjectNode) garage(root, "fx_g07_tiers").get("tiers").get(0)).remove("unit_min"),
				"fx_g07_tiers: tiers item", "missing [unit_min]");
		assertRejected(root -> ((ObjectNode) garage(root, "fx_g08_bands").get("bands").get(0)).put("label", "x"),
				"fx_g08_bands: bands item", "unknown [label]");
		assertRejected(root -> ((ArrayNode) root.get("garages")).add("fx_g99"), "a garages entry must be a JSON object");
		assertRejected(root -> garage(root, "fx_g07_tiers").putArray("tiers"), "fx_g07_tiers: tiers must be null or a non-empty");
		assertRejected(root -> garage(root, "fx_g08_bands").putArray("bands"), "fx_g08_bands: bands must be null or a non-empty");
	}

	@Test
	public void rejectsMistypedGarageValues() throws Exception {
		assertRejected(root -> garage(root, "fx_g01_core").put("hourly_rate_cents", 200.5), "garage fx_g01_core",
				"hourly_rate_cents", "integral");
		assertRejected(root -> garage(root, "fx_g01_core").put("billing_unit_min", "60"), "garage fx_g01_core",
				"billing_unit_min", "integral");
		assertRejected(root -> garage(root, "fx_g01_core").put("x_m", "580300"), "garage fx_g01_core",
				"x_m must be a finite number");
		assertRejected(root -> garage(root, "fx_g01_core").putNull("y_m"), "garage fx_g01_core", "y_m must be a finite number");
		assertRejected(root -> garage(root, "fx_g07_tiers").put("garage_id", " "), "garage_id");
		assertRejected(root -> ((ObjectNode) garage(root, "fx_g07_tiers").get("tiers").get(0)).put("price_cents", 30.5),
				"fx_g07_tiers", "tier price_cents", "integral");
		assertRejected(root -> ((ObjectNode) garage(root, "fx_g08_bands").get("bands").get(0)).put("kind", "flat"),
				"fx_g08_bands", "band kind flat is not one of free, total, increment");
	}

	@Test
	public void rejectsDuplicateGarageIds() throws Exception {
		assertRejected(root -> garage(root, "fx_g02_a").put("garage_id", "fx_g01_core"), "duplicate garage_id fx_g01_core");
	}

	/** Validation as strict as the Python dataclasses: structure, windows, caps, tiers, bands. */
	@Test
	public void rejectsAGarageThatBreaksTheRulesOfThePythonReference() throws Exception {
		// Exactly one tariff structure.
		assertRejected(root -> garage(root, "fx_g01_core").putNull("fee_end_s"), "garage fx_g01_core",
				"exactly one tariff structure");
		assertRejected(root -> {
			ObjectNode garage = garage(root, "fx_g07_tiers");
			garage.put("hourly_rate_cents", 100);
		}, "garage fx_g07_tiers", "a tiered garage leaves the single-window core empty");
		assertRejected(root -> garage(root, "fx_g08_bands").put("hourly_rate_cents", 100), "garage fx_g08_bands",
				"a banded garage leaves the rate and the billing unit empty");
		assertRejected(root -> garage(root, "fx_g08_bands").putNull("fee_start_s"), "garage fx_g08_bands",
				"a banded garage needs its fee window");
		assertRejected(root -> {
			ObjectNode garage = garage(root, "fx_g08_bands");
			garage.put("first_period_min", 30);
			garage.put("first_period_cents", 100);
		}, "garage fx_g08_bands", "a banded garage has no first period");
		// Windows and caps.
		assertRejected(root -> garage(root, "fx_g01_core").put("fee_end_s", 90000), "garage fx_g01_core", "fee window");
		assertRejected(root -> garage(root, "fx_g01_core").put("fee_start_s", 86400), "garage fx_g01_core", "fee window");
		assertRejected(root -> garage(root, "fx_g01_core").put("hourly_rate_cents", 0), "hourly_rate_cents must be at least 1");
		assertRejected(root -> garage(root, "fx_g01_core").put("billing_unit_min", 0), "billing_unit_min must be at least 1");
		assertRejected(root -> garage(root, "fx_g01_core").put("monthly_cents", 0), "monthly_cents must be at least 1");
		assertRejected(root -> garage(root, "fx_g01_core").put("x_m", Double.POSITIVE_INFINITY), "x_m must be a finite number");
		assertRejected(root -> garage(root, "fx_g09_fp").put("daily_cap_cents", 100), "garage fx_g09_fp",
				"daily_cap_cents 100 is below first_period_cents 110");
		assertRejected(root -> garage(root, "fx_g09_fp").putNull("first_period_cents"), "garage fx_g09_fp",
				"first_period_min and first_period_cents must be given together");
		assertRejected(root -> garage(root, "fx_g09_fp").putNull("first_period_end_s"), "garage fx_g09_fp",
				"first_period_start_s and first_period_end_s must be given together");
		assertRejected(root -> {
			ObjectNode garage = garage(root, "fx_g01_core");
			garage.put("first_period_start_s", 3600);
			garage.put("first_period_end_s", 7200);
		}, "garage fx_g01_core", "a first-period window needs a first period");
		assertRejected(root -> garage(root, "fx_g09_fp").put("first_period_end_s", 100), "garage fx_g09_fp",
				"first-period window");
	}

	@Test
	public void rejectsInvalidTiersAndBands() throws Exception {
		assertRejected(root -> ((ObjectNode) garage(root, "fx_g07_tiers").get("tiers").get(1)).put("unit_min", 60),
				"fx_g07_tiers", "must share one unit length");
		assertRejected(root -> ((ObjectNode) garage(root, "fx_g07_tiers").get("tiers").get(1)).put("start_s", 30000),
				"fx_g07_tiers", "tiers overlap");
		assertRejected(root -> ((ObjectNode) garage(root, "fx_g07_tiers").get("tiers").get(0)).put("end_s", 28800),
				"fx_g07_tiers", "no time-of-day interval of positive length");
		assertRejected(root -> ((ObjectNode) garage(root, "fx_g07_tiers").get("tiers").get(0)).put("price_cents", 0),
				"fx_g07_tiers", "positive price");
		assertRejected(root -> ((ObjectNode) garage(root, "fx_g08_bands").get("bands").get(1)).put("from_min", 25),
				"fx_g08_bands", "band 1 starts at 25 min but the band before ends at 20");
		assertRejected(root -> ((ObjectNode) garage(root, "fx_g08_bands").get("bands").get(0)).put("price_cents", 5),
				"fx_g08_bands", "band 0 is free");
		assertRejected(root -> ((ObjectNode) garage(root, "fx_g08_bands").get("bands").get(1)).put("price_cents", 0),
				"fx_g08_bands", "band 1 is a total");
		assertRejected(root -> ((ObjectNode) garage(root, "fx_g08_bands").get("bands").get(2)).putNull("unit_min"),
				"fx_g08_bands", "band 2 is an increment");
		assertRejected(root -> ((ObjectNode) garage(root, "fx_g08_bands").get("bands").get(1)).put("to_min", 20),
				"fx_g08_bands", "band 1 must end after it starts");
		// Only the last band may be open-ended.
		assertRejected(root -> ((ObjectNode) garage(root, "fx_g08_bands").get("bands").get(3)).putNull("to_min"),
				"fx_g08_bands", "only the last may be open-ended");
		// A tiered garage carries bands only as ONE closed free band (its grace period), never a schedule or a first period.
		assertRejected(root -> garage(root, "fx_g07_tiers").set("bands", garage(root, "fx_g08_bands").get("bands")),
				"fx_g07_tiers", "carries bands only as a grace period");
		assertRejected(root -> {
			ObjectNode graceGarage = garage(root, "fx_g15_tier_grace");
			graceGarage.put("first_period_min", 30);
			graceGarage.put("first_period_cents", 100);
		}, "fx_g15_tier_grace", "has no first period");
	}
}
