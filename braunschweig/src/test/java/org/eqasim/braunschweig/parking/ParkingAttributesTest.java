package org.eqasim.braunschweig.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.population.PopulationUtils;

/**
 * Reading the parking attributes of the plans (parkingZone, parkingFree and parkingDistrict of an activity,
 * residentParkingZone and residentParkingDistrict of a person) once, for the car cost model, the parking search time and
 * the startup check of the plans: one parsing and one message format that names the person, the plan and the activity, the
 * attribute, the value and the tariffs path. The fixture tariff model lists the districts fx_district_a, fx_district_b and
 * fx_district_sz_a and the zones of the golden fixture (the resident zone fx_res_a among them).
 */
public class ParkingAttributesTest {
	private static final String FIXTURE_RESOURCE = "/parking/parking_tariffs_fixture.json";
	private static final String TARIFFS_PATH = "parking_tariffs_fixture.json";
	private static final String TARIFFS_HINT = "of the parking tariff model " + TARIFFS_PATH
			+ " (braunschweigParking.tariffsPath); the tariff model and the plans must come from the same release";
	private static final String TYPE_HINT = "; the eqasim-bs plans writer writes it with this type";

	private static ParkingTariffs tariffs;

	@BeforeClass
	public static void readTariffs() throws Exception {
		URL resource = ParkingAttributesTest.class.getResource(FIXTURE_RESOURCE);
		assertNotNull("test resource " + FIXTURE_RESOURCE + " is missing", resource);
		tariffs = ParkingTariffs.read(Path.of(resource.toURI()));
	}

	private static Person person(String personId) {
		return PopulationUtils.getFactory().createPerson(Id.createPersonId(personId));
	}

	private static Activity activity(String type) {
		return PopulationUtils.createActivityFromCoord(type, new Coord(0, 0));
	}

	private static Activity with(Activity activity, String attribute, Object value) {
		activity.getAttributes().putAttribute(attribute, value);
		return activity;
	}

	/** A plan of the person whose main activities are joined by routed car trips (stage activities in between). */
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
		return plan;
	}

	private static ZoneTariff zone(Person person, Activity activity) {
		return ParkingAttributes.zone(tariffs, TARIFFS_PATH, person, activity);
	}

	@Test
	public void anActivityWithoutAZoneAttributeLiesInNoZone() {
		assertNull(zone(person("p1"), activity("work")));
	}

	@Test
	public void aKnownZoneIsReturnedAsItsTariff() {
		ZoneTariff tariff = zone(person("p1"), with(activity("work"), ZoneParkingCarCostModel.ZONE_ATTRIBUTE, "fx_bs_ia"));
		assertEquals(tariffs.zone("fx_bs_ia").orElseThrow(), tariff);
		assertEquals("fx_bs_ia", tariff.zoneId());
	}

	/**
	 * One format for every reader: the person, the plan and the activity (its type) as the startup check of the plans names
	 * them, the attribute, the value, and the tariffs path. The reader that does not know the position of the activity finds
	 * it among the plans of the person (the activity index counts the main activities of a plan from 0).
	 */
	@Test
	public void anUnknownOrMistypedZoneNamesPersonPlanActivityAttributeValueAndTariffsPath() {
		Person person = person("p1");
		plan(person, activity("home"), with(activity("work"), ZoneParkingCarCostModel.ZONE_ATTRIBUTE, "fx_bs_ia"));
		Activity shop = with(activity("shop"), ZoneParkingCarCostModel.ZONE_ATTRIBUTE, "bs_zone_unknown");
		Activity leisure = with(activity("leisure"), ZoneParkingCarCostModel.ZONE_ATTRIBUTE, 42);
		plan(person, activity("home"), activity("work"), shop, leisure);

		IllegalStateException unknown = assertThrows(IllegalStateException.class, () -> zone(person, shop));
		assertEquals("person p1, plan 1, activity 2 (shop): parkingZone bs_zone_unknown is not a zone " + TARIFFS_HINT,
				unknown.getMessage());
		IllegalStateException mistyped = assertThrows(IllegalStateException.class, () -> zone(person, leisure));
		assertEquals("person p1, plan 1, activity 3 (leisure): parkingZone must be a java.lang.String, got java.lang.Integer"
				+ " 42" + TYPE_HINT, mistyped.getMessage());
	}

	/** A reader that knows the position (the startup check of the plans) gets the same text without any search. */
	@Test
	public void aGivenPositionIsNamedAsGivenAndGivesTheTextOfTheSearch() {
		Person person = person("p1");
		Activity shop = with(activity("shop"), ZoneParkingCarCostModel.ZONE_ATTRIBUTE, "bs_zone_unknown");
		plan(person, activity("home"), shop);
		IllegalStateException searched = assertThrows(IllegalStateException.class, () -> zone(person, shop));
		IllegalStateException given = assertThrows(IllegalStateException.class,
				() -> ParkingAttributes.zone(tariffs, TARIFFS_PATH, person, 0, 1, shop));
		assertEquals(searched.getMessage(), given.getMessage());
		IllegalStateException elsewhere = assertThrows(IllegalStateException.class,
				() -> ParkingAttributes.zone(tariffs, TARIFFS_PATH, person, 4, 7, shop));
		assertEquals("person p1, plan 4, activity 7 (shop): parkingZone bs_zone_unknown is not a zone " + TARIFFS_HINT,
				elsewhere.getMessage());
	}

	/** An activity that is in none of the plans of the person (a synthetic trip) is named without a position. */
	@Test
	public void anActivityOutsideThePlansIsNamedWithoutAPosition() {
		Person person = person("p1");
		plan(person, activity("home"), activity("work"));
		Activity stray = with(activity("shop"), ZoneParkingCarCostModel.ZONE_ATTRIBUTE, "bs_zone_unknown");
		IllegalStateException error = assertThrows(IllegalStateException.class, () -> zone(person, stray));
		assertEquals("person p1, activity (shop) outside the plans of the person: parkingZone bs_zone_unknown is not a zone "
				+ TARIFFS_HINT, error.getMessage());
	}

	@Test
	public void theDistrictsOfAnActivityAndOfAHomeAreReadAndValidated() {
		Person person = person("p1");
		person.getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_DISTRICT_ATTRIBUTE, "fx_district_a");
		Activity inDistrict = with(activity("work"), ZoneParkingCarCostModel.DISTRICT_ATTRIBUTE, "fx_district_b");
		plan(person, activity("home"), inDistrict);
		assertEquals("fx_district_b", ParkingAttributes.activityDistrict(tariffs, TARIFFS_PATH, person,
				ParkingAttributes.UNKNOWN_POSITION, ParkingAttributes.UNKNOWN_POSITION, inDistrict));
		assertEquals("fx_district_a", ParkingAttributes.homeDistrict(tariffs, TARIFFS_PATH, person));
		// Absent attributes: no district.
		assertNull(ParkingAttributes.activityDistrict(tariffs, TARIFFS_PATH, person, 0, 0, activity("work")));
		assertNull(ParkingAttributes.homeDistrict(tariffs, TARIFFS_PATH, person("p2")));
	}

	/**
	 * An unknown or mistyped district fails naming the owner as the zone does: the activity (person, plan, activity) for
	 * the activity attribute, the person alone for the home district.
	 */
	@Test
	public void anUnknownOrMistypedDistrictNamesItsOwnerTheAttributeTheValueAndTheTariffsPath() {
		Person person = person("p1");
		person.getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_DISTRICT_ATTRIBUTE, "bs_district_unknown");
		Activity unknownDistrict = with(activity("work"), ZoneParkingCarCostModel.DISTRICT_ATTRIBUTE, "bs_district_x");
		Activity mistypedDistrict = with(activity("shop"), ZoneParkingCarCostModel.DISTRICT_ATTRIBUTE, 3);
		plan(person, activity("home"), unknownDistrict, mistypedDistrict);

		IllegalStateException unknownOnActivity = assertThrows(IllegalStateException.class, () -> ParkingAttributes
				.activityDistrict(tariffs, TARIFFS_PATH, person, ParkingAttributes.UNKNOWN_POSITION,
						ParkingAttributes.UNKNOWN_POSITION, unknownDistrict));
		assertEquals("person p1, plan 0, activity 1 (work): parkingDistrict bs_district_x is not a resident district "
				+ TARIFFS_HINT, unknownOnActivity.getMessage());
		IllegalStateException mistypedOnActivity = assertThrows(IllegalStateException.class, () -> ParkingAttributes
				.activityDistrict(tariffs, TARIFFS_PATH, person, 0, 2, mistypedDistrict));
		assertEquals("person p1, plan 0, activity 2 (shop): parkingDistrict must be a java.lang.String, got java.lang.Integer"
				+ " 3" + TYPE_HINT, mistypedOnActivity.getMessage());
		IllegalStateException unknownHome = assertThrows(IllegalStateException.class,
				() -> ParkingAttributes.homeDistrict(tariffs, TARIFFS_PATH, person));
		assertEquals("person p1: residentParkingDistrict bs_district_unknown is not a resident district " + TARIFFS_HINT,
				unknownHome.getMessage());

		Person numeric = person("p2");
		numeric.getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_DISTRICT_ATTRIBUTE, 7);
		IllegalStateException mistypedHome = assertThrows(IllegalStateException.class,
				() -> ParkingAttributes.homeDistrict(tariffs, TARIFFS_PATH, numeric));
		assertEquals("person p2: residentParkingDistrict must be a java.lang.String, got java.lang.Integer 7" + TYPE_HINT,
				mistypedHome.getMessage());
	}

	/** The free-parking flag is a Boolean; absent means false, and a mistyped flag names the activity as the zone does. */
	@Test
	public void theParkingFreeFlagIsReadAndAMistypedOneNamesPersonPlanAndActivity() {
		Person person = person("p1");
		Activity free = with(activity("work"), ZoneParkingCarCostModel.FREE_ATTRIBUTE, true);
		Activity notFree = with(activity("shop"), ZoneParkingCarCostModel.FREE_ATTRIBUTE, false);
		Activity text = with(activity("leisure"), ZoneParkingCarCostModel.FREE_ATTRIBUTE, "yes");
		plan(person, activity("home"), free, notFree, text);

		assertTrue(ParkingAttributes.parkingFree(person, free));
		assertFalse(ParkingAttributes.parkingFree(person, notFree));
		assertFalse(ParkingAttributes.parkingFree(person, activity("work")));
		IllegalStateException searched = assertThrows(IllegalStateException.class,
				() -> ParkingAttributes.parkingFree(person, text));
		assertEquals("person p1, plan 0, activity 3 (leisure): parkingFree must be a java.lang.Boolean, got java.lang.String yes"
				+ TYPE_HINT, searched.getMessage());
		IllegalStateException given = assertThrows(IllegalStateException.class,
				() -> ParkingAttributes.parkingFree(person, 2, 5, text));
		assertEquals("person p1, plan 2, activity 5 (leisure): parkingFree must be a java.lang.Boolean, got java.lang.String yes"
				+ TYPE_HINT, given.getMessage());
	}

	/** The resident zone of a person is returned as its tariff; an unknown or a mistyped id names the person. */
	@Test
	public void theResidentZoneOfAPersonIsReadAndAnUnknownOrMistypedOneNamesThePerson() {
		Person resident = person("p1");
		resident.getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, "fx_res_a");
		ZoneTariff tariff = ParkingAttributes.residentZone(tariffs, TARIFFS_PATH, resident);
		assertEquals(tariffs.zone("fx_res_a").orElseThrow(), tariff);
		// No attribute: the person lives in no resident zone. The zone type is the business of the startup check.
		assertNull(ParkingAttributes.residentZone(tariffs, TARIFFS_PATH, person("p2")));
		Person streetZone = person("p3");
		streetZone.getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, "fx_bs_ia");
		assertEquals("fx_bs_ia", ParkingAttributes.residentZone(tariffs, TARIFFS_PATH, streetZone).zoneId());

		Person unknown = person("p4");
		unknown.getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, "bs_res_unknown");
		IllegalStateException unknownError = assertThrows(IllegalStateException.class,
				() -> ParkingAttributes.residentZone(tariffs, TARIFFS_PATH, unknown));
		assertEquals("person p4: residentParkingZone bs_res_unknown is not a zone " + TARIFFS_HINT, unknownError.getMessage());
		Person numeric = person("p5");
		numeric.getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, 5);
		IllegalStateException mistyped = assertThrows(IllegalStateException.class,
				() -> ParkingAttributes.residentZone(tariffs, TARIFFS_PATH, numeric));
		assertEquals("person p5: residentParkingZone must be a java.lang.String, got java.lang.Integer 5" + TYPE_HINT,
				mistyped.getMessage());
	}

	/**
	 * The readers run for every priced car stay and for every activity of the population, so the happy path builds no
	 * name: the person is not even asked for its id or its plans (a name is built only for an error message).
	 */
	@Test
	public void theHappyPathBuildsNoNames() {
		Person real = person("p1");
		real.getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_DISTRICT_ATTRIBUTE, "fx_district_a");
		real.getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, "fx_res_a");
		Map<String, Integer> calls = new HashMap<>();
		Person counting = (Person) Proxy.newProxyInstance(Person.class.getClassLoader(), new Class<?>[] { Person.class },
				(proxy, method, arguments) -> {
					calls.merge(method.getName(), 1, Integer::sum);
					try {
						return method.invoke(real, arguments);
					} catch (InvocationTargetException error) {
						throw error.getCause();
					}
				});
		Activity activity = with(with(with(activity("work"), ZoneParkingCarCostModel.ZONE_ATTRIBUTE, "fx_bs_ia"),
				ZoneParkingCarCostModel.DISTRICT_ATTRIBUTE, "fx_district_a"), ZoneParkingCarCostModel.FREE_ATTRIBUTE, true);

		assertNotNull(ParkingAttributes.zone(tariffs, TARIFFS_PATH, counting, activity));
		assertTrue(ParkingAttributes.parkingFree(counting, activity));
		assertEquals("fx_district_a", ParkingAttributes.activityDistrict(tariffs, TARIFFS_PATH, counting,
				ParkingAttributes.UNKNOWN_POSITION, ParkingAttributes.UNKNOWN_POSITION, activity));
		assertEquals("fx_district_a", ParkingAttributes.homeDistrict(tariffs, TARIFFS_PATH, counting));
		assertNotNull(ParkingAttributes.residentZone(tariffs, TARIFFS_PATH, counting));
		assertFalse(calls.toString(), calls.containsKey("getId"));
		assertFalse(calls.toString(), calls.containsKey("getPlans"));
		assertFalse(calls.toString(), calls.containsKey("getSelectedPlan"));
	}
}
