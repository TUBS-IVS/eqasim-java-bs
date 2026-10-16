package org.eqasim.braunschweig.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.URL;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.Level;
import org.eqasim.braunschweig.parking.ParkingLogCapture.Event;
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
 * fx_sz and fx_wob are street_paid, fx_res_a is the resident_zone, fx_campus the campus; the model lists the resident
 * districts fx_district_a, fx_district_b and fx_district_sz_a (spec Amendment C3). One test per failure rule and one
 * success case that pins the coverage counts and the log line.
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
		return check(null);
	}

	/** The check of an enabled module with the given searchTimeFactor, or without that parameter when null. */
	private ParkingPopulationCheck check(String searchTimeFactor) {
		ParkingConfigGroup parking = new ParkingConfigGroup();
		parking.setEnabled("true");
		parking.setTariffsPath(TARIFFS_PATH);
		if (searchTimeFactor != null) {
			parking.addParam("searchTimeFactor", searchTimeFactor);
		}
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

	private static Activity inDistrict(Activity activity, String districtId) {
		activity.getAttributes().putAttribute(ZoneParkingCarCostModel.DISTRICT_ATTRIBUTE, districtId);
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

	/**
	 * With the garage options on (the fixture model has garage_decay_m 400) an activity in a zone needs a coordinate to find
	 * its garages: the run fails at the controller start, naming the person, the activity and the way out, not at the first
	 * car stay of a replanning iteration. Activities outside every zone need none, and with the options off neither do the
	 * zoned ones.
	 */
	@Test
	public void aZonedActivityWithoutACoordinateFailsWhenTheGarageOptionsAreOn() throws Exception {
		Activity noCoordinate = PopulationUtils.createActivityFromLinkId("work", Id.createLinkId("link"));
		noCoordinate.getAttributes().putAttribute(ZoneParkingCarCostModel.ZONE_ATTRIBUTE, "fx_bs_ib");
		plan(person("p1"), activity("home"), noCoordinate, activity("home"));
		assertStartupFails("person p1, plan 0, activity 1 (work)", "has no coordinate", "garage_decay_m 400.0 m",
				"garage_decay_commute_m 250.0 m", "set garage_decay_m and garage_decay_commute_m to 0", TARIFFS_PATH);

		// Outside every zone no coordinate is needed.
		population.removePerson(Id.createPersonId("p1"));
		Activity outsideZone = PopulationUtils.createActivityFromLinkId("work", Id.createLinkId("link"));
		plan(person("p2"), activity("home"), outsideZone, activity("home"));
		check().notifyStartup(new StartupEvent(null));

		// With the garage options off the zoned activity needs none either.
		population.removePerson(Id.createPersonId("p2"));
		Activity zonedWithoutCoordinate = PopulationUtils.createActivityFromLinkId("work", Id.createLinkId("link"));
		zonedWithoutCoordinate.getAttributes().putAttribute(ZoneParkingCarCostModel.ZONE_ATTRIBUTE, "fx_bs_ib");
		plan(person("p3"), activity("home"), zonedWithoutCoordinate, activity("home"));
		com.fasterxml.jackson.databind.node.ObjectNode tree = (com.fasterxml.jackson.databind.node.ObjectNode)
				new com.fasterxml.jackson.databind.ObjectMapper().readTree(Path.of(
						ParkingPopulationCheckTest.class.getResource(FIXTURE_RESOURCE).toURI()).toFile());
		tree.put("garage_decay_m", 0.0);
		tree.put("garage_decay_commute_m", 0.0);
		ParkingConfigGroup parking = new ParkingConfigGroup();
		parking.setEnabled("true");
		parking.setTariffsPath(TARIFFS_PATH);
		new ParkingPopulationCheck(ParkingTariffs.parse(tree), population, parking).notifyStartup(new StartupEvent(null));
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
	 * Counts over every plan of every person without stage activities: a resident of fx_res_a and fx_district_a works in
	 * fx_bs_ib with free parking and shops in fx_res_a inside fx_district_a, the district of the home (rule R2 applies:
	 * fx_res_a honours resident permits); a visitor without a home district has a campus plan with a stay in the district
	 * fx_district_b and an unselected plan with a stay in fx_sz; an outsider of fx_district_b works outside every zone,
	 * inside fx_district_b, with an explicit parkingFree = false (legitimate: free parking outside a zone costs nothing
	 * either way; a district outside every zone is legitimate, the districts are independent of the fee zones).
	 */
	@Test
	public void countsTheCoverageOfEveryPlanWithoutStageActivities() {
		Person resident = person("resident");
		resident.getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, "fx_res_a");
		resident.getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_DISTRICT_ATTRIBUTE, "fx_district_a");
		Activity freeWork = inZone("work", "fx_bs_ib");
		freeWork.getAttributes().putAttribute(ZoneParkingCarCostModel.FREE_ATTRIBUTE, true);
		plan(resident, activity("home"), freeWork, inDistrict(inZone("shop", "fx_res_a"), "fx_district_a"), activity("home"));

		Person visitor = person("visitor");
		plan(visitor, activity("home"), inDistrict(inZone("leisure", "fx_campus"), "fx_district_b"), activity("home"));
		plan(visitor, activity("home"), inZone("leisure", "fx_sz"), activity("home"));

		Person outsider = person("outsider");
		outsider.getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_DISTRICT_ATTRIBUTE, "fx_district_b");
		Activity outsideWork = inDistrict(activity("work"), "fx_district_b");
		outsideWork.getAttributes().putAttribute(ZoneParkingCarCostModel.FREE_ATTRIBUTE, false);
		plan(outsider, activity("home"), outsideWork, activity("home"));

		ParkingPopulationCheck check = check();
		Coverage coverage = check.scan();
		Map<ZoneType, Long> byZoneType = new EnumMap<>(ZoneType.class);
		byZoneType.put(ZoneType.STREET_PAID, 2L);
		byZoneType.put(ZoneType.RESIDENT_ZONE, 1L);
		byZoneType.put(ZoneType.CAMPUS, 1L);
		assertEquals(new Coverage(3, 13, byZoneType, 1, 1, 3, 2, 1, 0, 0), coverage);
		assertEquals(4, coverage.activitiesInAZone());
		assertFalse(coverage.noActivityInAZone());
		assertEquals("[parking] population: 3 persons, 13 activities; 4 in a zone (30.8 %: street_paid 2, resident_zone"
				+ " 1, campus 1), 1 parkingFree, 1 persons with residentParkingZone (33.3 %); 3 in a resident district"
				+ " (23.1 %), 2 persons with residentParkingDistrict (66.7 %), 1 in the own district and a zone that"
				+ " honours resident permits (7.7 %); 0 in a zone with a search time (0.0 %); 0 persons with parkingEvExempt"
				+ " (0.0 %)",
				ParkingPopulationCheck.summary(coverage));
		// The startup event scans the same population and passes.
		check.notifyStartup(new StartupEvent(null));
	}

	/**
	 * Lever 4: the activities whose zone has a search time above 0 minutes are the ones the search time factor acts on;
	 * the rate shows that the zone column reaches the population (fx_bs_ia_v2 5 min and fx_wob_v2 2 min count,
	 * fx_campus_v2 with an explicit 0 and fx_sz without a value do not).
	 */
	@Test
	public void countsTheActivitiesInAZoneWithASearchTime() {
		plan(person("p1"), activity("home"), inZone("work", "fx_bs_ia_v2"), inZone("shop", "fx_wob_v2"),
				inZone("leisure", "fx_campus_v2"), inZone("shop", "fx_sz"), activity("home"));
		Coverage coverage = check().scan();
		assertEquals(6, coverage.activities());
		assertEquals(2, coverage.activitiesWithSearchTime());
		assertTrue(ParkingPopulationCheck.summary(coverage), ParkingPopulationCheck.summary(coverage)
				.contains("2 in a zone with a search time (33.3 %)"));
	}

	/**
	 * The events the startup check of the population logs for a check with the given searchTimeFactor, without the EV
	 * exemption warning (the populations of the search time tests carry no exempt person while the fixture has zones with an
	 * exemption; that warning is pinned by its own tests).
	 */
	private List<Event> startup(String searchTimeFactor) {
		ParkingPopulationCheck check = check(searchTimeFactor);
		return ParkingLogCapture.capture(ParkingPopulationCheck.class, () -> check.notifyStartup(new StartupEvent(null)))
				.stream().filter(event -> !(event.level() == Level.WARN
						&& event.message().contains(ZoneParkingCarCostModel.EV_EXEMPT_ATTRIBUTE))).toList();
	}

	/**
	 * Lever 4 configured and the zone column filled (three zones), but no activity of the population lies in a zone with a
	 * search time: the factor cannot act in this run, which is a WARNING (a model without any search time fails the run in
	 * the check of the tariff model). The coverage line stays an information with its rate.
	 */
	@Test
	public void aSearchTimeFactorWithoutAnyActivityInASearchTimeZoneWarns() {
		plan(person("p1"), activity("home"), inZone("work", "fx_bs_ib"), inZone("shop", "fx_sz"), activity("home"));
		List<Event> events = startup("1.0");
		List<String> warnings = ParkingLogCapture.messages(events, Level.WARN);
		assertEquals(warnings.toString(), 1, warnings.size());
		for (String fragment : new String[] { "searchTimeFactor 1.0", "3 zones carry a search_time_min above 0",
				"no activity of the population lies in such a zone", "cannot act in this run" }) {
			assertTrue("'" + fragment + "' missing in: " + warnings.get(0), warnings.get(0).contains(fragment));
		}
		List<String> information = ParkingLogCapture.messages(events, Level.INFO);
		assertEquals(information.toString(), 1, information.size());
		assertTrue(information.get(0), information.get(0).contains("0 in a zone with a search time (0.0 %)"));
	}

	/**
	 * A destination of purpose home gets no search time (rule H1, ruling R-T3-a), so a home activity in a zone with a search
	 * time is not one lever 4 acts on: it is not counted, and a population whose only activities in such zones are at home
	 * still gets the warning that the factor cannot act. A non-home activity in the same zone is counted.
	 */
	@Test
	public void aHomeActivityInASearchTimeZoneIsNotCountedAndDoesNotSilenceTheWarning() {
		plan(person("p1"), inZone("home", "fx_bs_ia_v2"), inZone("work", "fx_sz"), inZone("home", "fx_bs_ia_v2"));
		Coverage coverage = check().scan();
		assertEquals(0, coverage.activitiesWithSearchTime());
		List<String> warnings = ParkingLogCapture.messages(startup("1.0"), Level.WARN);
		assertEquals(warnings.toString(), 1, warnings.size());
		assertTrue(warnings.get(0), warnings.get(0).contains("home destinations excluded"));

		plan(person("p2"), activity("home"), inZone("shop", "fx_bs_ia_v2"), activity("home"));
		assertEquals(1, check().scan().activitiesWithSearchTime());
		assertTrue(ParkingLogCapture.messages(startup("1.0"), Level.WARN).isEmpty());
	}

	/** An activity in a zone with a search time, or a factor of 0 (off), gives no search time warning. */
	@Test
	public void noSearchTimeWarningWhenTheTermCanActOrTheFactorIsZero() {
		plan(person("p1"), activity("home"), inZone("work", "fx_bs_ia_v2"), activity("home"));
		assertTrue(ParkingLogCapture.messages(startup("1.0"), Level.WARN).isEmpty());

		population.removePerson(Id.createPersonId("p1"));
		plan(person("p2"), activity("home"), inZone("work", "fx_sz"), activity("home"));
		for (String factor : new String[] { null, "0", "0.0" }) {
			assertTrue(factor, ParkingLogCapture.messages(startup(factor), Level.WARN).isEmpty());
		}
	}

	/**
	 * Rule R2 (spec Amendment C3): the plan attributes parkingDistrict and residentParkingDistrict name districts of the
	 * tariff model's resident_districts; an id the model does not list fails at startup naming the person, the plan and
	 * activity or the person alone, the attribute, the value and the tariffs path, because a stay in an unknown district
	 * would otherwise be priced as if it lay in none.
	 */
	@Test
	public void anUnknownParkingDistrictOrResidentDistrictFails() {
		plan(person("p1"), activity("home"), inDistrict(inZone("work", "fx_bs_ib"), "bs_district_unknown"), activity("home"));
		assertStartupFails("person p1, plan 0, activity 1 (work): parkingDistrict bs_district_unknown is not a resident"
				+ " district of the parking tariff model " + TARIFFS_PATH);

		population.removePerson(Id.createPersonId("p1"));
		Person resident = person("p2");
		resident.getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_DISTRICT_ATTRIBUTE, "bs_district_unknown");
		plan(resident, activity("home"), activity("work"), activity("home"));
		assertStartupFails("person p2: residentParkingDistrict bs_district_unknown is not a resident district of the parking"
				+ " tariff model " + TARIFFS_PATH);
	}

	/** The fixture lists the districts of two municipalities; any of them is a valid value for either attribute. */
	@Test
	public void everyDistrictOfTheTariffModelIsValid() {
		for (ParkingTariffs.ResidentDistrict district : tariffs.residentDistricts()) {
			Person person = person("p_" + district.districtId());
			person.getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_DISTRICT_ATTRIBUTE, district.districtId());
			plan(person, activity("home"), inDistrict(activity("work"), district.districtId()), activity("home"));
		}
		check().notifyStartup(new StartupEvent(null));
		assertEquals(3, check().scan().districtResidents());
	}

	/** The plans writer types the district attributes (String ids); another type fails at startup. */
	@Test
	public void mistypedDistrictAttributesFail() {
		Activity numericDistrict = activity("work");
		numericDistrict.getAttributes().putAttribute(ZoneParkingCarCostModel.DISTRICT_ATTRIBUTE, 3);
		Person p1 = person("p1");
		plan(p1, activity("home"), numericDistrict, activity("home"));
		assertStartupFails("person p1, plan 0, activity 1 (work): parkingDistrict must be a java.lang.String, got"
				+ " java.lang.Integer 3");

		population.removePerson(p1.getId());
		Person p2 = person("p2");
		p2.getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_DISTRICT_ATTRIBUTE, 3);
		plan(p2, activity("home"), activity("work"), activity("home"));
		assertStartupFails("person p2: residentParkingDistrict must be a java.lang.String, got java.lang.Integer 3");
	}

	/**
	 * A schema-1 tariff model lists no district, so plans that carry district attributes (written for a model of schema 2)
	 * fail at startup instead of silently matching nothing: the tariff model and the plans must come from the same release.
	 */
	@Test
	public void aSchemaOneModelListsNoDistrictAndRejectsEveryDistrictValue() throws Exception {
		URL resource = ParkingPopulationCheckTest.class.getResource(ParkingCostCalculatorTest.SCHEMA_1_TARIFFS_RESOURCE);
		assertNotNull(resource);
		ParkingTariffs schemaOne = ParkingTariffs.read(Path.of(resource.toURI()));
		ParkingConfigGroup parking = new ParkingConfigGroup();
		parking.setEnabled("true");
		parking.setTariffsPath(TARIFFS_PATH);
		plan(person("p1"), activity("home"), inDistrict(inZone("work", "fx_bs_ib"), "fx_district_a"), activity("home"));
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> new ParkingPopulationCheck(schemaOne, population, parking).notifyStartup(new StartupEvent(null)));
		assertTrue(error.getMessage(), error.getMessage().contains("parkingDistrict fx_district_a is not a resident district"));

		// Plans without district attributes pass against a schema-1 model, as they did under version 1.
		population.removePerson(Id.createPersonId("p1"));
		plan(person("p2"), activity("home"), inZone("work", "fx_bs_ib"), activity("home"));
		new ParkingPopulationCheck(schemaOne, population, parking).notifyStartup(new StartupEvent(null));
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
				+ " campus 0), 0 parkingFree, 0 persons with residentParkingZone (0.0 %); 0 in a resident district (0.0 %),"
				+ " 0 persons with residentParkingDistrict (0.0 %), 0 in the own district and a zone that honours resident"
				+ " permits (0.0 %); 0 in a zone with a search time (0.0 %); 0 persons with parkingEvExempt (0.0 %); no activity"
				+ " lies in a parking zone although braunschweigParking is enabled, so every car stay is priced NO_ZONE: were"
				+ " the plans written with parking_zones_enabled?",
				ParkingPopulationCheck.summary(coverage));

		// An empty population prices nothing and is not flagged.
		population.removePerson(Id.createPersonId("p1"));
		assertFalse(check().scan().noActivityInAZone());
	}

	// ---------------------------------------------------------------------------- EV exemption (spec v3 A5)

	private static Person exempt(Person person) {
		person.getAttributes().putAttribute(ZoneParkingCarCostModel.EV_EXEMPT_ATTRIBUTE, true);
		return person;
	}

	/** The persons with parkingEvExempt = true are counted (false and absent are not) and their rate is on the line. */
	@Test
	public void countsThePersonsWithTheEvExemption() {
		plan(exempt(person("p1")), activity("home"), inZone("work", "fx_ev_street_v3"), activity("home"));
		Person explicitFalse = person("p2");
		explicitFalse.getAttributes().putAttribute(ZoneParkingCarCostModel.EV_EXEMPT_ATTRIBUTE, false);
		plan(explicitFalse, activity("home"), inZone("work", "fx_bs_ib"), activity("home"));
		plan(person("p3"), activity("home"), inZone("work", "fx_bs_ib"), activity("home"));
		plan(exempt(person("p4")), activity("home"), inZone("work", "fx_bs_ib"), activity("home"));
		Coverage coverage = check().scan();
		assertEquals(2, coverage.evExemptPersons());
		assertTrue(ParkingPopulationCheck.summary(coverage), ParkingPopulationCheck.summary(coverage)
				.contains("; 2 persons with parkingEvExempt (50.0 %)"));
		// Both zones with an exemption and persons with the flag: nothing to warn about.
		assertTrue(check().evExemptionWarning(coverage).isEmpty());
	}

	/**
	 * Fallback transparency: zones with ev_free_minutes but no exempt person (the plans were written without the flag) cannot
	 * act, and exempt persons without a zone that grants the exemption (here the model of schema 1) cannot either; both are
	 * warned about at startup, and a population with both is not.
	 */
	@Test
	public void warnsWhenTheEvExemptionCannotActInThisRun() throws Exception {
		plan(person("p1"), activity("home"), inZone("work", "fx_ev_street_v3"), activity("home"));
		Optional<String> noPersons = check().evExemptionWarning(check().scan());
		assertTrue(noPersons.isPresent());
		for (String fragment : new String[] { "2 zones state ev_free_minutes", "no person carries parkingEvExempt",
				"cannot act in this run" }) {
			assertTrue("'" + fragment + "' missing in: " + noPersons.get(), noPersons.get().contains(fragment));
		}
		List<String> startupWarnings = ParkingLogCapture.messages(
				ParkingLogCapture.capture(ParkingPopulationCheck.class, () -> check().notifyStartup(new StartupEvent(null))),
				Level.WARN);
		assertEquals(startupWarnings.toString(), 1, startupWarnings.size());

		population.removePerson(Id.createPersonId("p1"));
		plan(exempt(person("p2")), activity("home"), inZone("work", "fx_bs_ib"), activity("home"));
		URL schemaOneResource = ParkingPopulationCheckTest.class.getResource("/parking/parking_tariffs_fixture_schema1.json");
		ParkingTariffs schemaOne = ParkingTariffs.read(Path.of(schemaOneResource.toURI()));
		ParkingConfigGroup parking = new ParkingConfigGroup();
		parking.setEnabled("true");
		parking.setTariffsPath("parking_tariffs_fixture_schema1.json");
		ParkingPopulationCheck oldModel = new ParkingPopulationCheck(schemaOne, population, parking);
		String warning = oldModel.evExemptionWarning(oldModel.scan()).orElseThrow();
		for (String fragment : new String[] { "1 persons carry parkingEvExempt", "no zone of the tariff model",
				"states ev_free_minutes (schema 1)" }) {
			assertTrue("'" + fragment + "' missing in: " + warning, warning.contains(fragment));
		}
	}

	/** A parkingEvExempt that is not a Boolean fails at the start, naming the person and the type the writer writes. */
	@Test
	public void aMistypedEvExemptionFlagFailsAtStartup() {
		Person person = person("p1");
		person.getAttributes().putAttribute(ZoneParkingCarCostModel.EV_EXEMPT_ATTRIBUTE, "true");
		plan(person, activity("home"), inZone("work", "fx_bs_ib"), activity("home"));
		assertStartupFails("person p1", "parkingEvExempt must be a java.lang.Boolean", "java.lang.String true");
	}
}
