package org.eqasim.braunschweig.fares.zonal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

public class VrbFareModelTest {
	/** Small synthetic fare model with the same key set as braunschweig.data.vrb.fare_model_export writes. */
	public static final String JSON = """
			{"schema_version": 1, "tariff_snapshot_date": "2026-06-20", "money_price_year": 2026, "currency": "EUR",
			 "zones": ["40", "70"], "city_zones": ["40"], "price_classes": ["city", "ps1", "ps2", "ps3", "ps4"],
			 "single_adult_cents": {"city": 360, "ps1": 390, "ps2": 560, "ps3": 770, "ps4": 1230},
			 "single_child_cents": {"city": 210, "ps1": 230, "ps2": 330, "ps3": 460, "ps4": 740},
			 "short_trip_cents": 200, "short_trip_maximum_stop_intervals": 3,
			 "day_ticket_cents": {"city": 720, "ps1": 780, "ps2": 1120, "ps3": 1540, "ps4": 2460},
			 "validity_minutes": {"city": 90, "ps1": 90, "ps2": 90, "ps3": 120, "ps4": 150},
			 "price_class_by_pair": {"40|40": "city", "40|70": "ps2", "70|40": "ps2", "70|70": "ps1"},
			 "category_holder": {"deutschlandticket": "national_flat", "monthly_or_annual_subscription": "vrb_flat",
			                     "single_ticket": "none"},
			 "child_minimum_age": 6, "child_maximum_age": 14,
			 "external": {"rail_distance_bands_adult": [{"up_to_km": 5, "price_cents": 200}, {"up_to_km": 20, "price_cents": 500}],
			              "rail_distance_bands_child": [{"up_to_km": 5, "price_cents": 100}, {"up_to_km": 20, "price_cents": 250}],
			              "distance_factor": 1.0, "local_single_cents": 370},
			 "fallback": {"unsupported_ride_cents": 370}, "sources": [], "assumptions": []}
			""";

	public static VrbFareModel model() throws Exception {
		return VrbFareModel.parse(new ObjectMapper().readTree(JSON));
	}

	@Test
	public void parsesPricesPairsAndHolders() throws Exception {
		VrbFareModel model = model();
		assertEquals("ps2", model.priceClass("40", "70").orElseThrow());
		assertEquals("city", model.priceClass("40", "40").orElseThrow());
		assertFalse(model.priceClass("40", "99").isPresent());
		assertEquals(560, model.singleCents("ps2", false));
		assertEquals(330, model.singleCents("ps2", true));
		assertEquals(1120, model.dayTicketCents("ps2"));
		assertTrue(model.covers("ps3", "ps1"));
		assertTrue(model.covers("ps1", "ps1"));
		assertFalse(model.covers("city", "ps1"));
		assertThrows(IllegalArgumentException.class, () -> model.covers("ps9", "city"));
		assertEquals(VrbFareModel.Holder.NATIONAL_FLAT, model.holder("deutschlandticket"));
		assertEquals(VrbFareModel.Holder.VRB_FLAT, model.holder("monthly_or_annual_subscription"));
		assertEquals(VrbFareModel.Holder.NONE, model.holder("single_ticket"));
		assertNull(model.holder("something_else"));
		assertEquals(200, model.shortTripCents());
		assertEquals(3, model.shortTripMaximumStopIntervals());
		assertEquals(6, model.childMinimumAge());
		assertEquals(14, model.childMaximumAge());
		assertEquals(370, model.externalLocalSingleCents());
		assertEquals(370, model.fallbackCents());
	}

	@Test
	public void railBandsPickTheFirstCoveringTierAndRefuseBeyondTheLast() throws Exception {
		VrbFareModel model = model();
		assertEquals(200, model.railBandCents(4.9, false).orElseThrow());
		assertEquals(500, model.railBandCents(5.1, false).orElseThrow());
		assertEquals(250, model.railBandCents(5.1, true).orElseThrow());
		assertTrue(model.railBandCents(20.5, false).isEmpty());
	}

	/** Cross-language contract: the JSON written by the Python exporter (see resources README) must parse. */
	@Test
	public void parsesTheModelWrittenByThePythonExporter() throws Exception {
		java.nio.file.Path path = java.nio.file.Path.of(getClass().getResource("/vrb-zone-fares/vrb_fare_model_2026.json").toURI());
		VrbFareModel model = VrbFareModel.read(path);
		assertEquals(48, model.zones().size());
		assertEquals("ps2", model.priceClass("40", "70").orElseThrow());
		assertEquals("ps3", model.priceClass("40", "20").orElseThrow());
		assertEquals("city", model.priceClass("40", "40").orElseThrow());
		assertFalse(model.priceClass("55", "55").isPresent());
		assertEquals(360, model.singleCents("city", false));
		assertEquals(1230, model.singleCents("ps4", false));
		assertEquals(2460, model.dayTicketCents("ps4"));
		assertEquals(VrbFareModel.Holder.NATIONAL_FLAT, model.holder("job_or_semester_ticket"));
		assertEquals(VrbFareModel.Holder.VRB_FLAT, model.holder("weekly_monthly_no_subscription"));
		assertEquals(VrbFareModel.Holder.NONE, model.holder("never_pt"));
		assertEquals(150, model.railBandCents(0.5, false).orElseThrow());
		assertEquals(75, model.railBandCents(0.5, true).orElseThrow());
	}

	@Test
	public void rejectsIncompleteClassMapsAndUnknownPairClasses() throws Exception {
		String unknownClass = JSON.replace("\"ps4\": 1230", "\"ps4\": 1230, \"extra\": 1");
		assertThrows(IllegalArgumentException.class, () -> VrbFareModel.parse(new ObjectMapper().readTree(unknownClass)));
		String badPair = JSON.replace("\"40|70\": \"ps2\"", "\"40|70\": \"ps9\"");
		assertThrows(IllegalArgumentException.class, () -> VrbFareModel.parse(new ObjectMapper().readTree(badPair)));
		String wrongSchema = JSON.replace("\"schema_version\": 1", "\"schema_version\": 2");
		assertThrows(IllegalArgumentException.class, () -> VrbFareModel.parse(new ObjectMapper().readTree(wrongSchema)));
	}
}
