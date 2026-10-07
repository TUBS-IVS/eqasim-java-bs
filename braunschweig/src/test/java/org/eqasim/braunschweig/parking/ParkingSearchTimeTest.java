package org.eqasim.braunschweig.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.URL;
import java.nio.file.Path;

import org.junit.BeforeClass;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.core.population.PopulationUtils;

/**
 * Parking search time of a destination (lever 4 of the zone-based parking costs): the search_time_min of the zone named
 * by the activity attribute parkingZone, 0 outside every zone, for a zone without the column, for a destination of
 * purpose home (rule H1: parking at home is free and needs no search) and without the parking module. The fixture tariff
 * model gives fx_bs_ia_v2 and fx_bs_ib_v2 5 min, fx_wob_v2 2 min, fx_campus_v2 an explicit 0 min and every other zone no
 * value.
 */
public class ParkingSearchTimeTest {
	private static final String FIXTURE_RESOURCE = "/parking/parking_tariffs_fixture.json";
	private static final String TARIFFS_PATH = "parking_tariffs_fixture.json";

	private static ParkingTariffs tariffs;

	@BeforeClass
	public static void readTariffs() throws Exception {
		URL resource = ParkingSearchTimeTest.class.getResource(FIXTURE_RESOURCE);
		assertNotNull("test resource " + FIXTURE_RESOURCE + " is missing", resource);
		tariffs = ParkingTariffs.read(Path.of(resource.toURI()));
	}

	/** The search time of an enabled module with the given searchTimeFactor, or without that parameter when null. */
	private static ParkingSearchTime searchTime(String searchTimeFactor) {
		ParkingConfigGroup parking = new ParkingConfigGroup();
		parking.setEnabled("true");
		parking.setTariffsPath(TARIFFS_PATH);
		if (searchTimeFactor != null) {
			parking.addParam("searchTimeFactor", searchTimeFactor);
		}
		return new ParkingSearchTime(tariffs, parking);
	}

	private static Activity activity(String type, String zoneId) {
		Activity activity = PopulationUtils.createActivityFromCoord(type, new Coord(0, 0));
		if (zoneId != null) {
			activity.getAttributes().putAttribute(ZoneParkingCarCostModel.ZONE_ATTRIBUTE, zoneId);
		}
		return activity;
	}

	@Test
	public void minutesAreTheSearchTimeColumnOfTheDestinationZone() {
		ParkingSearchTime searchTime = searchTime("1.0");
		assertEquals(5.0, searchTime.minutes(activity("shop", "fx_bs_ia_v2")), 0.0);
		assertEquals(5.0, searchTime.minutes(activity("work", "fx_bs_ib_v2")), 0.0);
		assertEquals(2.0, searchTime.minutes(activity("leisure", "fx_wob_v2")), 0.0);
		// A zone with an explicit 0 and a zone without the column ("0 where empty") both give 0 minutes.
		assertEquals(0.0, searchTime.minutes(activity("work", "fx_campus_v2")), 0.0);
		assertEquals(0.0, searchTime.minutes(activity("shop", "fx_bs_ia")), 0.0);
		assertEquals(0.0, searchTime.minutes(activity("shop", "fx_res_a")), 0.0);
	}

	/** Outside every zone (assumption Z1) there is no search time. */
	@Test
	public void aDestinationOutsideEveryZoneHasNoSearchTime() {
		assertEquals(0.0, searchTime("1.0").minutes(activity("shop", null)), 0.0);
	}

	/**
	 * The minutes do not depend on the factor, only the term in the utility does. A destination of purpose home gets no
	 * search time whatever the factor and the zone (ASSUMPTION, ruling R-T3-a: parking at home is free, rule H1, and needs
	 * no search): the purpose test is the one of the home rule of the fee.
	 */
	@Test
	public void minutesDoNotDependOnTheFactorAndAreZeroAtHome() {
		for (String factor : new String[] { null, "0", "0.0", "2.5", "10" }) {
			ParkingSearchTime searchTime = searchTime(factor);
			assertEquals(5.0, searchTime.minutes(activity("shop", "fx_bs_ia_v2")), 0.0);
			assertEquals(5.0, searchTime.minutes(activity("work", "fx_bs_ia_v2")), 0.0);
			assertEquals(0.0, searchTime.minutes(activity("home", "fx_bs_ia_v2")), 0.0);
			assertEquals(0.0, searchTime.minutes(activity("home", "fx_wob_v2")), 0.0);
		}
	}

	/**
	 * The one predicate of where lever 4 acts, shared by the car utility term and the coverage count of the startup check
	 * of the population: the zone column, 0 where it is empty or an explicit 0, and 0 for the home purpose.
	 */
	@Test
	public void theSinglePredicateOfWhereTheSearchTimeActsIsTheZoneColumnExceptAtHome() {
		ZoneTariff withSearchTime = tariffs.zone("fx_bs_ia_v2").orElseThrow();
		ZoneTariff withoutValue = tariffs.zone("fx_bs_ia").orElseThrow();
		ZoneTariff explicitZero = tariffs.zone("fx_campus_v2").orElseThrow();
		assertEquals(5, ParkingSearchTime.minutesIn(withSearchTime, "work"));
		assertEquals(5, ParkingSearchTime.minutesIn(withSearchTime, "shop"));
		assertEquals(0, ParkingSearchTime.minutesIn(withSearchTime, "home"));
		assertEquals(0, ParkingSearchTime.minutesIn(withoutValue, "work"));
		assertEquals(0, ParkingSearchTime.minutesIn(explicitZero, "work"));
	}

	@Test
	public void theFactorIsTheOneOfTheModuleAndZeroWhenAbsent() {
		assertEquals(0.0, searchTime(null).factor(), 0.0);
		assertEquals(0.0, searchTime("0.0").factor(), 0.0);
		assertEquals(1.0, searchTime("1").factor(), 0.0);
		assertEquals(2.5, searchTime("2.5").factor(), 0.0);
		assertEquals(10.0, searchTime("10.0").factor(), 0.0);
	}

	/** Without the module the search time is the neutral element: factor 0 and 0 minutes, whatever the attributes say. */
	@Test
	public void withoutTheModuleThereIsNoSearchTimeAnywhere() {
		ParkingSearchTime none = ParkingSearchTime.none();
		assertSame(none, ParkingSearchTime.none());
		assertEquals(0.0, none.factor(), 0.0);
		assertEquals(0.0, none.minutes(activity("shop", "fx_bs_ia_v2")), 0.0);
		assertEquals(0.0, none.minutes(activity("shop", null)), 0.0);
		// It does not even read the attribute: an id it cannot know is no error without the module.
		assertEquals(0.0, none.minutes(activity("shop", "any_zone")), 0.0);
		Activity mistyped = activity("shop", null);
		mistyped.getAttributes().putAttribute(ZoneParkingCarCostModel.ZONE_ATTRIBUTE, 42);
		assertEquals(0.0, none.minutes(mistyped), 0.0);
	}

	/** A zone the tariff model does not contain, or a zone attribute of the wrong type, fails instead of giving 0. */
	@Test
	public void anUnknownOrMistypedZoneFailsNamingTheAttributeAndTheTariffs() {
		ParkingSearchTime searchTime = searchTime("1.0");
		IllegalStateException unknown = assertThrows(IllegalStateException.class,
				() -> searchTime.minutes(activity("work", "bs_zone_unknown")));
		for (String fragment : new String[] { "work", ZoneParkingCarCostModel.ZONE_ATTRIBUTE, "bs_zone_unknown",
				TARIFFS_PATH }) {
			assertTrue(unknown.getMessage(), unknown.getMessage().contains(fragment));
		}

		Activity mistyped = activity("work", null);
		mistyped.getAttributes().putAttribute(ZoneParkingCarCostModel.ZONE_ATTRIBUTE, 42);
		IllegalStateException wrongType = assertThrows(IllegalStateException.class, () -> searchTime.minutes(mistyped));
		assertTrue(wrongType.getMessage(), wrongType.getMessage().contains(ZoneParkingCarCostModel.ZONE_ATTRIBUTE));
		assertTrue(wrongType.getMessage(), wrongType.getMessage().contains("java.lang.Integer"));
	}

	@Test
	public void theModuleSearchTimeNeedsTheTariffModel() {
		assertThrows(NullPointerException.class, () -> new ParkingSearchTime(null, new ParkingConfigGroup()));
	}
}
