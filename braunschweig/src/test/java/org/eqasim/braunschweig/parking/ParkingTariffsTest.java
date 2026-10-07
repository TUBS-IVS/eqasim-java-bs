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
 * Reading the parking tariff model JSON (design section 5.4; schema 1 of version 1 and schema 2 of the v2 levers and the
 * resident districts). The rejection cases mutate the parsed fixture tree, so they hold for any formatting of
 * {@code parking/parking_tariffs_fixture.json}, a byte-for-byte copy of the schema-2 model
 * scripts/export_parking_golden_cases.py writes (eqasim-bs tests/fixtures/parking), and of
 * {@code parking/parking_tariffs_fixture_schema1.json}, the schema-1 model of version 1, which the reader must keep
 * accepting. Only facts of the fixture tariff table are pinned, not the exporter's metadata (assumption texts, sources).
 */
public class ParkingTariffsTest {
	private static final String FIXTURE_RESOURCE = "/parking/parking_tariffs_fixture.json";
	private static final String SCHEMA_1_RESOURCE = ParkingCostCalculatorTest.SCHEMA_1_TARIFFS_RESOURCE;
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The zones of the schema-1 fixture of version 1; the schema-2 fixture holds them unchanged and ten more. */
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
		assertEquals(2, tariffs.schemaVersion());
		assertEquals(Set.of("fx_bs_ia", "fx_bs_ib", "fx_sz", "fx_wob", "fx_pe", "fx_res_a", "fx_campus", "fx_frac",
				"fx_bs_ia_v2", "fx_bs_ib_v2", "fx_wob_v2", "fx_campus_v2", "fx_garage_window_v2", "fx_capped_street_v2",
				"fx_campus_tie_v2", "fx_res_garage_v2", "fx_bga_v2", "fx_res_nopermit_v2"), tariffs.zones());
		assertNotNull(tariffs.tariffSnapshotDate());
		assertEquals(ParkingCostCalculator.TERMINAL_STAY_RULE_UNTIL_FEE_END, tariffs.terminalStayRule());
		assertFalse(tariffs.assumptions().isEmpty());
		assertEquals(2, tariffs.sources().size());

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
				"fx_campus_v2", "fx_capped_street_v2", "fx_frac", "fx_garage_window_v2", "fx_pe", "fx_res_a",
				"fx_res_garage_v2", "fx_res_nopermit_v2", "fx_sz", "fx_wob", "fx_wob_v2"), List.copyOf(tariffs.zones()));
		assertThrows(UnsupportedOperationException.class, () -> tariffs.zones().add("extra"));
		assertThrows(UnsupportedOperationException.class, () -> tariffs.zones().remove("fx_sz"));
		assertThrows(UnsupportedOperationException.class, () -> tariffs.assumptions().add("ASSUMPTION: extra"));
		assertThrows(UnsupportedOperationException.class, () -> tariffs.sources().clear());
	}

	/** Schema 1 and schema 2 are accepted; any other version, and any non-integral one, fails with the version. */
	@Test
	public void rejectsAnotherSchemaVersion() throws Exception {
		assertRejected(root -> root.put("schema_version", 3), "schema_version must be one of [1, 2], got 3");
		assertRejected(root -> root.put("schema_version", 0), "schema_version must be one of [1, 2], got 0");
		assertRejected(root -> root.put("schema_version", -1), "schema_version must be one of [1, 2], got -1");
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
		ObjectNode schema3 = fixtureTree();
		schema3.put("schema_version", 3);
		schema3.remove("weekday_only");
		schema3.remove("resident_districts");
		schema3.put("service_days", "weekday");
		zone(schema3, "fx_sz").remove("garage_daily_cap_cents");
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ParkingTariffs.parse(schema3));
		assertEquals("parking_tariffs: schema_version must be one of [1, 2], got 3", error.getMessage());

		ObjectNode unversioned = fixtureTree();
		unversioned.remove("schema_version");
		error = assertThrows(IllegalArgumentException.class, () -> ParkingTariffs.parse(unversioned));
		assertTrue(error.getMessage(), error.getMessage().startsWith("parking_tariffs: schema_version must be"));
	}

	/** After the version, the key set of THAT version is checked: schema 1 has no district list and no schema-2 zone keys. */
	@Test
	public void checksTheKeySetOfTheVersionTheDocumentDeclares() throws Exception {
		// A schema-2 document declaring schema 1 has the keys of schema 2 too many.
		assertRejected(root -> root.put("schema_version", 1), "must have exactly the schema-1 fields",
				"unknown [resident_districts]");
		// A schema-1 document declaring schema 2 lacks the district list.
		ObjectNode schemaOne = schemaOneTree();
		assertRejected(schemaOne, root -> root.put("schema_version", 2), "must have exactly the schema-2 fields",
				"missing [resident_districts]");
		// A schema-1 zone has none of the ten schema-2 keys; a schema-2 zone has all of them.
		assertRejected(schemaOneTree(), root -> zone(root, "fx_sz").put("search_time_min", 5), "zone fx_sz",
				"must have exactly the schema-1 fields", "unknown [search_time_min]");
		assertRejected(root -> zone(root, "fx_sz").remove("search_time_min"), "zone fx_sz",
				"must have exactly the schema-2 fields", "missing [search_time_min]");
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
	 * A stay above the maximum stay loses the street product (spec lever 2), so a maximum stay needs a long-stay product
	 * or a garage; the commuter product alone does not suffice, because it serves work and education only.
	 */
	@Test
	public void rejectsAMaximumStayWithoutAnyProductAboveIt() throws Exception {
		String message = "max_stay_min is set but neither long_stay_product_cents nor the garage core is";
		assertRejected(root -> zone(root, "fx_bs_ia").putNull("long_stay_product_cents"), "parking zone fx_bs_ia", message);
		assertRejected(root -> zone(root, "fx_pe").putNull("long_stay_product_cents"), "parking zone fx_pe", message);
		assertRejected(root -> zone(root, "fx_res_a").putNull("long_stay_product_cents"), "parking zone fx_res_a", message);
		// The street zone with a maximum stay and a garage but no long-stay product is valid; without the garage core it is not,
		// also not with a commuter product.
		ObjectNode root = fixtureTree();
		for (String field : List.of("garage_hourly_rate_cents", "garage_billing_unit_min", "garage_fee_start_s", "garage_fee_end_s",
				"garage_daily_cap_cents")) {
			zone(root, "fx_bs_ia_v2").putNull(field);
		}
		zone(root, "fx_bs_ia_v2").put("commuter_day_cents", 376);
		assertRejected(root, ignored -> {
		}, "parking zone fx_bs_ia_v2", message);
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
						"member_day_cents", "guest_day_cents", "commuter_day_cents"), //
				"fx_campus", List.of("hourly_rate_cents", "billing_unit_min", "free_if_stay_at_most_min", "first_period_min",
						"first_period_cents", "daily_cap_cents", "max_stay_min", "long_stay_product_cents"));
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
		assertEquals(2 + 7 + 8 + 7, rejected);
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
		root.put("schema_version", 3);
		Path file = temporary.newFile("parking_tariffs_schema_3.json").toPath();
		MAPPER.writeValue(file.toFile(), root);
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ParkingTariffs.read(file));
		assertTrue(error.getMessage(), error.getMessage().contains("parking_tariffs_schema_3.json"));
		assertTrue(error.getMessage(), error.getMessage().contains("schema_version must be one of [1, 2], got 3"));
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
		assertEquals(info.toString(), 1, info.size());
		assertTrue(info.get(0), info.get(0).contains("3 zone(s) with a search time"));

		ParkingTariffs[] readSchemaOne = new ParkingTariffs[1];
		List<Event> schemaOneEvents = ParkingLogCapture.captureChecked(ParkingTariffs.class,
				() -> readSchemaOne[0] = ParkingTariffs.read(resourcePath(SCHEMA_1_RESOURCE)));
		assertEquals(0L, readSchemaOne[0].zonesWithSearchTime());
		List<String> schemaOneInfo = ParkingLogCapture.messages(schemaOneEvents, Level.INFO);
		assertTrue(schemaOneInfo.toString(), schemaOneInfo.get(0).contains("0 zone(s) with a search time"));
	}
}
