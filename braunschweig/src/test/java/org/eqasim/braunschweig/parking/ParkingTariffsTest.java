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

import org.eqasim.braunschweig.parking.ZoneTariff.ZoneType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Reading the parking tariff model JSON (design section 5.4, schema 1). The rejection cases mutate the parsed fixture
 * tree, so they hold for any formatting of {@code parking/parking_tariffs_fixture.json}, which is replaced by the file
 * the Python exporter writes. Only facts of the plan's fixture tariff table are pinned, not the exporter's metadata.
 */
public class ParkingTariffsTest {
	private static final String FIXTURE_RESOURCE = "/parking/parking_tariffs_fixture.json";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Rule
	public TemporaryFolder temporary = new TemporaryFolder();

	private static Path fixturePath() throws Exception {
		URL resource = ParkingTariffsTest.class.getResource(FIXTURE_RESOURCE);
		assertNotNull("test resource " + FIXTURE_RESOURCE + " is missing", resource);
		return Path.of(resource.toURI());
	}

	private static ObjectNode fixtureTree() throws Exception {
		return (ObjectNode) MAPPER.readTree(fixturePath().toFile());
	}

	private static ObjectNode zone(ObjectNode root, String zoneId) {
		return (ObjectNode) root.get("zones").get(zoneId);
	}

	/** Applies one mutation to a fresh fixture tree; the model must be rejected with every fragment in the message. */
	private static void assertRejected(Consumer<ObjectNode> mutation, String... fragments) throws Exception {
		ObjectNode root = fixtureTree();
		mutation.accept(root);
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ParkingTariffs.parse(root));
		for (String fragment : fragments) {
			assertTrue("'" + fragment + "' missing in: " + error.getMessage(), error.getMessage().contains(fragment));
		}
	}

	@Test
	public void readsTheFixtureTariffModel() throws Exception {
		ParkingTariffs tariffs = ParkingTariffs.read(fixturePath());
		assertEquals(Set.of("fx_bs_ia", "fx_bs_ib", "fx_sz", "fx_wob", "fx_pe", "fx_res_a", "fx_campus"), tariffs.zones());
		assertNotNull(tariffs.tariffSnapshotDate());
		assertEquals(ParkingCostCalculator.TERMINAL_STAY_RULE_UNTIL_FEE_END, tariffs.terminalStayRule());
		assertFalse(tariffs.assumptions().isEmpty());
		assertFalse(tariffs.sources().isEmpty());

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

		ZoneTariff residents = tariffs.zone("fx_res_a").orElseThrow();
		assertEquals(ZoneType.RESIDENT_ZONE, residents.zoneType());
		assertTrue(residents.residentExempt());
		assertEquals(OptionalLong.of(0), residents.hourlyRateCents());
		assertEquals(OptionalInt.of(120), residents.maxStayMinutes());
		assertEquals(OptionalLong.of(900), residents.longStayProductCents());

		ZoneTariff campus = tariffs.zone("fx_campus").orElseThrow();
		assertEquals(ZoneType.CAMPUS, campus.zoneType());
		assertEquals(OptionalLong.of(350), campus.memberDayCents());
		assertEquals(OptionalLong.of(900), campus.guestDayCents());
		assertEquals(OptionalLong.empty(), campus.hourlyRateCents());
		assertEquals(OptionalInt.empty(), campus.billingUnitMinutes());

		assertEquals(OptionalLong.of(600), tariffs.zone("fx_wob").orElseThrow().dailyCapCents());
		assertEquals(21600, tariffs.zone("fx_wob").orElseThrow().feeStart_s());
	}

	/** Both fixtures describe the plan's seven zones; a drift between them would make one of the test classes moot. */
	@Test
	public void fixtureZonesEqualTheGoldenCaseTariffs() throws Exception {
		ParkingTariffs tariffs = ParkingTariffs.read(fixturePath());
		Map<String, ZoneTariff> golden = ParkingCostCalculatorTest.goldenTariffs();
		assertEquals(golden.keySet(), tariffs.zones());
		for (String zoneId : tariffs.zones()) {
			assertEquals(zoneId, golden.get(zoneId), tariffs.zone(zoneId).orElseThrow());
		}
	}

	@Test
	public void zoneIdsAreSortedAndImmutableAndAnUnknownIdIsEmpty() throws Exception {
		ParkingTariffs tariffs = ParkingTariffs.read(fixturePath());
		assertTrue(tariffs.zone("bs_zone_unknown").isEmpty());
		assertEquals(List.of("fx_bs_ia", "fx_bs_ib", "fx_campus", "fx_pe", "fx_res_a", "fx_sz", "fx_wob"),
				List.copyOf(tariffs.zones()));
		assertThrows(UnsupportedOperationException.class, () -> tariffs.zones().add("extra"));
		assertThrows(UnsupportedOperationException.class, () -> tariffs.zones().remove("fx_sz"));
		assertThrows(UnsupportedOperationException.class, () -> tariffs.assumptions().add("ASSUMPTION: extra"));
		assertThrows(UnsupportedOperationException.class, () -> tariffs.sources().clear());
	}

	@Test
	public void rejectsAnotherSchemaVersion() throws Exception {
		assertRejected(root -> root.put("schema_version", 2), "schema_version must be 1, got 2");
		assertRejected(root -> root.put("schema_version", 1.5), "schema_version", "integral");
		assertRejected(root -> root.put("schema_version", "1"), "schema_version", "integral");
	}

	@Test
	public void rejectsAnUnknownZoneType() throws Exception {
		assertRejected(root -> zone(root, "fx_pe").put("zone_type", "garage"), "zone fx_pe", "unknown zone_type garage",
				"street_paid");
		// JSON names are exact: a differently spelled known type is unknown, not guessed.
		assertRejected(root -> zone(root, "fx_pe").put("zone_type", "STREET_PAID"), "zone fx_pe",
				"unknown zone_type STREET_PAID");
	}

	@Test
	public void rejectsAMaximumStayWithoutALongStayProduct() throws Exception {
		assertRejected(root -> zone(root, "fx_bs_ia").putNull("long_stay_product_cents"), "parking zone fx_bs_ia",
				"long_stay_product_cents is required when max_stay_min is set");
		assertRejected(root -> zone(root, "fx_pe").putNull("long_stay_product_cents"), "parking zone fx_pe",
				"long_stay_product_cents is required when max_stay_min is set");
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
				"hourly_rate_cents must be 0 for a resident_zone");
		assertRejected(root -> zone(root, "fx_campus").putNull("member_day_cents"), "parking zone fx_campus",
				"member_day_cents is required for a campus");
		assertRejected(root -> zone(root, "fx_campus").putNull("guest_day_cents"), "parking zone fx_campus",
				"guest_day_cents is required for a campus");

		// Design 3.1 does not require a billing unit for a resident zone: its zero rate needs none.
		ObjectNode root = fixtureTree();
		zone(root, "fx_res_a").putNull("billing_unit_min");
		assertEquals(OptionalInt.empty(), ParkingTariffs.parse(root).zone("fx_res_a").orElseThrow().billingUnitMinutes());
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
	}

	/** Every object carries exactly its schema-1 keys: a dropped or renamed field is not read as null. */
	@Test
	public void rejectsMissingOrUnknownKeys() throws Exception {
		assertRejected(root -> zone(root, "fx_bs_ia").remove("daily_cap_cents"), "zone fx_bs_ia",
				"missing [daily_cap_cents]");
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

	@Test
	public void readNamesTheFileOfAnInvalidModel() throws Exception {
		ObjectNode root = fixtureTree();
		root.put("schema_version", 2);
		Path file = temporary.newFile("parking_tariffs_schema_2.json").toPath();
		MAPPER.writeValue(file.toFile(), root);
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ParkingTariffs.read(file));
		assertTrue(error.getMessage(), error.getMessage().contains("parking_tariffs_schema_2.json"));
		assertTrue(error.getMessage(), error.getMessage().contains("schema_version must be 1"));
	}
}
