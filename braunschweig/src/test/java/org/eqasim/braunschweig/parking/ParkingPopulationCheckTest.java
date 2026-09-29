package org.eqasim.braunschweig.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.URL;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import org.eqasim.braunschweig.parking.ParkingPopulationCheck.Coverage;
import org.eqasim.braunschweig.parking.ZoneTariff.ZoneType;
import org.junit.BeforeClass;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.population.PopulationUtils;

/**
 * Startup check of the plans against the tariff model (design "parking cost zones"; eqasim-bs issue #436) on synthetic
 * populations and the fixture tariff model {@code parking/parking_tariffs_fixture.json}: fx_bs_ia, fx_bs_ib, fx_pe,
 * fx_sz and fx_wob are street_paid, fx_res_a is the resident_zone, fx_campus the campus. One test per failure rule and
 * one success case that pins the coverage counts and the log line.
 */
public class ParkingPopulationCheckTest {
	private static final String FIXTURE_RESOURCE = "/parking/parking_tariffs_fixture.json";
	private static final String TARIFFS_PATH = "parking_tariffs_fixture.json";

	private static ParkingTariffs tariffs;

	private final Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());

	@BeforeClass
	public static void readTariffs() throws Exception {
		URL resource = ParkingPopulationCheckTest.class.getResource(FIXTURE_RESOURCE);
		assertNotNull("test resource " + FIXTURE_RESOURCE + " is missing", resource);
		tariffs = ParkingTariffs.read(Path.of(resource.toURI()));
	}

	private ParkingPopulationCheck check() {
		ParkingConfigGroup parking = new ParkingConfigGroup();
		parking.setEnabled("true");
		parking.setTariffsPath(TARIFFS_PATH);
		return new ParkingPopulationCheck(tariffs, population, parking);
	}

	private Person person(String personId) {
		Person person = population.getFactory().createPerson(Id.createPersonId(personId));
		population.addPerson(person);
		return person;
	}

	/**
	 * Adds a plan of the given activities, joined by routed car trips (car interaction - car - car interaction), as the
	 * prepared population carries them; the first plan of a person is its selected plan.
	 */
	private static Plan plan(Person person, Activity... activities) {
		Plan plan = PopulationUtils.createPlan(person);
		for (int index = 0; index < activities.length; index++) {
			if (index > 0) {
				plan.addActivity(PopulationUtils.createStageActivityFromCoordLinkIdAndModePrefix(new Coord(0, 0),
						Id.createLinkId("from"), TransportMode.car));
				plan.addLeg(PopulationUtils.createLeg(TransportMode.car));
				plan.addActivity(PopulationUtils.createStageActivityFromCoordLinkIdAndModePrefix(new Coord(0, 0),
						Id.createLinkId("to"), TransportMode.car));
			}
			plan.addActivity(activities[index]);
		}
		person.addPlan(plan);
		if (person.getSelectedPlan() == null) {
			person.setSelectedPlan(plan);
		}
		return plan;
	}

	private static Activity activity(String type) {
		return PopulationUtils.createActivityFromCoord(type, new Coord(0, 0));
	}

	private static Activity inZone(String type, String zoneId) {
		Activity activity = activity(type);
		activity.getAttributes().putAttribute(ZoneParkingCarCostModel.ZONE_ATTRIBUTE, zoneId);
		return activity;
	}

	/** The startup event fails with an IllegalStateException whose message carries every fragment. */
	private void assertStartupFails(String... fragments) {
		ParkingPopulationCheck check = check();
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> check.notifyStartup(new StartupEvent(null)));
		for (String fragment : fragments) {
			assertTrue("'" + fragment + "' missing in: " + error.getMessage(), error.getMessage().contains(fragment));
		}
	}

	@Test
	public void anUnknownParkingZoneFails() {
		plan(person("p1"), activity("home"), inZone("work", "bs_zone_unknown"), activity("home"));
		assertStartupFails("person p1, plan 0, activity 1 (work): parkingZone bs_zone_unknown is not a zone of the"
				+ " parking tariff model " + TARIFFS_PATH);
	}

	@Test
	public void anUnknownResidentZoneFails() {
		Person person = person("p1");
		person.getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, "bs_res_unknown");
		plan(person, activity("home"), activity("work"), activity("home"));
		assertStartupFails("person p1: residentParkingZone bs_res_unknown is not a zone of the parking tariff model "
				+ TARIFFS_PATH);
	}

	/** Only a resident_zone exempts its residents; a resident zone id naming another zone type is a writer defect. */
	@Test
	public void aResidentZoneOfAnotherZoneTypeFails() {
		Person street = person("p1");
		street.getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, "fx_bs_ia");
		plan(street, activity("home"), activity("work"), activity("home"));
		assertStartupFails("person p1: residentParkingZone fx_bs_ia is a street_paid zone of the parking tariff model "
				+ TARIFFS_PATH, "not a resident_zone");

		population.removePerson(street.getId());
		Person campus = person("p2");
		campus.getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, "fx_campus");
		plan(campus, activity("home"), activity("education"), activity("home"));
		assertStartupFails("person p2: residentParkingZone fx_campus is a campus zone of the parking tariff model "
				+ TARIFFS_PATH, "not a resident_zone");
	}

	/** The plans writer draws free parking only inside a zone: parkingFree = true without a parkingZone is a writer bug. */
	@Test
	public void parkingFreeWithoutAParkingZoneFails() {
		Activity work = activity("work");
		work.getAttributes().putAttribute(ZoneParkingCarCostModel.FREE_ATTRIBUTE, true);
		plan(person("p1"), activity("home"), work, activity("home"));
		assertStartupFails("person p1, plan 0, activity 1 (work): parkingFree true without a parkingZone", TARIFFS_PATH);
	}

	/**
	 * The legacy 8 km ring (isParis) and the zones are mutually exclusive: an isParis attribute of any value, on a person
	 * or on an activity, shows plans written with the ring on.
	 */
	@Test
	public void theLegacyRingAttributeFailsOnAPersonAndOnAnActivity() {
		Person ringResident = person("p1");
		ringResident.getAttributes().putAttribute(ParkingPopulationCheck.LEGACY_RING_ATTRIBUTE, false);
		plan(ringResident, activity("home"), activity("work"), activity("home"));
		assertStartupFails("person p1: isParis false", "8 km ring", "enable_urban_parking", "parking_zones_enabled",
				TARIFFS_PATH);

		population.removePerson(ringResident.getId());
		Activity ringShop = inZone("shop", "fx_bs_ia");
		ringShop.getAttributes().putAttribute(ParkingPopulationCheck.LEGACY_RING_ATTRIBUTE, true);
		plan(person("p2"), activity("home"), activity("work"), ringShop, activity("home"));
		assertStartupFails("person p2, plan 0, activity 2 (shop): isParis true", "8 km ring", "enable_urban_parking",
				"parking_zones_enabled", TARIFFS_PATH);
	}

	/** The plans writer types the attributes (String zone ids, Boolean parkingFree); another type fails at startup. */
	@Test
	public void mistypedAttributesFail() {
		Activity numericZone = activity("work");
		numericZone.getAttributes().putAttribute(ZoneParkingCarCostModel.ZONE_ATTRIBUTE, 42);
		Person p1 = person("p1");
		plan(p1, activity("home"), numericZone, activity("home"));
		assertStartupFails("person p1, plan 0, activity 1 (work): parkingZone must be a java.lang.String, got"
				+ " java.lang.Integer 42");

		population.removePerson(p1.getId());
		Activity textualFree = inZone("work", "fx_bs_ib");
		textualFree.getAttributes().putAttribute(ZoneParkingCarCostModel.FREE_ATTRIBUTE, "true");
		Person p2 = person("p2");
		plan(p2, activity("home"), textualFree, activity("home"));
		assertStartupFails("person p2, plan 0, activity 1 (work): parkingFree must be a java.lang.Boolean, got"
				+ " java.lang.String true");

		population.removePerson(p2.getId());
		Person p3 = person("p3");
		p3.getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, 7);
		plan(p3, activity("home"), activity("work"), activity("home"));
		assertStartupFails("person p3: residentParkingZone must be a java.lang.String, got java.lang.Integer 7");
	}

	/** Every plan is checked, not only the selected one: replanning may select any of them. */
	@Test
	public void aViolationInAnUnselectedPlanFails() {
		Person person = person("p1");
		Plan selected = plan(person, activity("home"), inZone("work", "fx_bs_ib"), activity("home"));
		plan(person, activity("home"), inZone("shop", "bs_zone_unknown"), activity("home"));
		assertEquals(selected, person.getSelectedPlan());
		assertStartupFails("person p1, plan 1, activity 1 (shop): parkingZone bs_zone_unknown");
	}

	/**
	 * Counts over every plan of every person without stage activities: a resident of fx_res_a works in fx_bs_ib with
	 * free parking and shops in fx_res_a; a visitor has a campus plan and an unselected plan with a stay in fx_sz; an
	 * outsider works outside every zone with an explicit parkingFree = false (legitimate: free parking outside a zone
	 * costs nothing either way).
	 */
	@Test
	public void countsTheCoverageOfEveryPlanWithoutStageActivities() {
		Person resident = person("resident");
		resident.getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, "fx_res_a");
		Activity freeWork = inZone("work", "fx_bs_ib");
		freeWork.getAttributes().putAttribute(ZoneParkingCarCostModel.FREE_ATTRIBUTE, true);
		plan(resident, activity("home"), freeWork, inZone("shop", "fx_res_a"), activity("home"));

		Person visitor = person("visitor");
		plan(visitor, activity("home"), inZone("leisure", "fx_campus"), activity("home"));
		plan(visitor, activity("home"), inZone("leisure", "fx_sz"), activity("home"));

		Activity outsideWork = activity("work");
		outsideWork.getAttributes().putAttribute(ZoneParkingCarCostModel.FREE_ATTRIBUTE, false);
		plan(person("outsider"), activity("home"), outsideWork, activity("home"));

		ParkingPopulationCheck check = check();
		Coverage coverage = check.scan();
		Map<ZoneType, Long> byZoneType = new EnumMap<>(ZoneType.class);
		byZoneType.put(ZoneType.STREET_PAID, 2L);
		byZoneType.put(ZoneType.RESIDENT_ZONE, 1L);
		byZoneType.put(ZoneType.CAMPUS, 1L);
		assertEquals(new Coverage(3, 13, byZoneType, 1, 1), coverage);
		assertEquals(4, coverage.activitiesInAZone());
		assertFalse(coverage.noActivityInAZone());
		assertEquals("[parking] population: 3 persons, 13 activities; 4 in a zone (30.8 %: street_paid 2, resident_zone"
				+ " 1, campus 1), 1 parkingFree, 1 persons with residentParkingZone (33.3 %)",
				ParkingPopulationCheck.summary(coverage));
		// The startup event scans the same population and passes.
		check.notifyStartup(new StartupEvent(null));
	}

	/**
	 * Fallback transparency: with the module on, a population in which no activity lies in a zone prices every car stay
	 * NO_ZONE, the signature of plans written without the zones; the line says so (and is logged as a warning).
	 */
	@Test
	public void aPopulationWithoutAZonedActivityIsFlagged() {
		plan(person("p1"), activity("home"), activity("work"), activity("home"));
		Coverage coverage = check().scan();
		assertTrue(coverage.noActivityInAZone());
		assertEquals("[parking] population: 1 persons, 3 activities; 0 in a zone (0.0 %: street_paid 0, resident_zone 0,"
				+ " campus 0), 0 parkingFree, 0 persons with residentParkingZone (0.0 %); no activity lies in a parking zone"
				+ " although braunschweigParking is enabled, so every car stay is priced NO_ZONE: were the plans written"
				+ " with parking_zones_enabled?", ParkingPopulationCheck.summary(coverage));

		// An empty population prices nothing and is not flagged.
		population.removePerson(Id.createPersonId("p1"));
		assertFalse(check().scan().noActivityInAZone());
	}
}
