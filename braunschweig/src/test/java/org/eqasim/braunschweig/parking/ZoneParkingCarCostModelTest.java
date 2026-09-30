package org.eqasim.braunschweig.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.eqasim.braunschweig.mode_choice.parameters.BraunschweigCostParameters;
import org.junit.Before;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.core.config.groups.PlansConfigGroup.ActivityDurationInterpretation;
import org.matsim.core.config.groups.PlansConfigGroup.TripDurationHandling;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

/**
 * Car cost of one trip with zone-based parking (design "parking cost zones", sections 3.2 and 3.3): the driving cost
 * plus the parking cost at the destination, priced with the fixture tariff model
 * {@code parking/parking_tariffs_fixture.json} (fixture tariffs pin the arithmetic, not real tariffs). Every trip
 * drives {@value #CAR_DISTANCE_M} m at {@value #CAR_COST_EUR_KM} EUR/km, i.e. 2.50 EUR driving cost, and its car leg
 * takes {@value #CAR_TRAVEL_TIME_S} s, so the car arrives 30 minutes after the trip departs. Times of day on day 0:
 * 28800 = 08:00, 32400 = 09:00, 36000 = 10:00, 61200 = 17:00, 70200 = 19:30, 72000 = 20:00.
 */
public class ZoneParkingCarCostModelTest {
	private static final String FIXTURE_RESOURCE = "/parking/parking_tariffs_fixture.json";
	private static final String TARIFFS_PATH = "parking_tariffs_fixture.json";

	private static final double CAR_COST_EUR_KM = 0.2;
	private static final double CAR_DISTANCE_M = 12500.0;
	private static final double DRIVING_COST_EUR = CAR_COST_EUR_KM * CAR_DISTANCE_M / 1000.0;
	private static final double CAR_TRAVEL_TIME_S = 1800.0;
	private static final double EUR_TOLERANCE = 1e-9;

	private ParkingOutcomeCounter counter;
	private ZoneParkingCarCostModel model;

	@Before
	public void setUp() throws Exception {
		counter = new ParkingOutcomeCounter();
		model = model(eqasimTimeInterpretation(), counter);
	}

	/**
	 * The time interpretation of an eqasim run: GenerateConfig sets shiftActivityEndTimes and keeps the MATSim default
	 * activity duration interpretation tryEndTimeThenDuration.
	 */
	private static TimeInterpretation eqasimTimeInterpretation() {
		return TimeInterpretation.create(ActivityDurationInterpretation.tryEndTimeThenDuration,
				TripDurationHandling.shiftActivityEndTimes);
	}

	private static ZoneParkingCarCostModel model(TimeInterpretation timeInterpretation, ParkingOutcomeCounter counter)
			throws Exception {
		BraunschweigCostParameters costParameters = new BraunschweigCostParameters();
		costParameters.carCost_EUR_km = CAR_COST_EUR_KM;
		ParkingConfigGroup parking = new ParkingConfigGroup();
		parking.setEnabled("true");
		parking.setTariffsPath(TARIFFS_PATH);
		return new ZoneParkingCarCostModel(costParameters, timeInterpretation, ParkingTariffs.read(fixturePath()), counter,
				parking);
	}

	private static Path fixturePath() throws Exception {
		URL resource = ZoneParkingCarCostModelTest.class.getResource(FIXTURE_RESOURCE);
		assertNotNull("test resource " + FIXTURE_RESOURCE + " is missing", resource);
		return Path.of(resource.toURI());
	}

	/** A car trip of one person: the person, whose selected plan holds the trip's activities, the trip and its route. */
	private record CarTrip(Person person, Plan plan, DiscreteModeChoiceTrip trip, List<PlanElement> elements) {
	}

	/** Destination activity of the given type, ending at {@code end_s}, or without an end when {@code end_s} is null. */
	private static Activity activity(String type, Double end_s) {
		Activity activity = PopulationUtils.createActivityFromCoord(type, new Coord(1000, 0));
		if (end_s != null) {
			activity.setEndTime(end_s);
		}
		return activity;
	}

	/**
	 * A person whose selected plan is home - car - destination, followed by car - home unless the destination is
	 * terminal (the last plan element); the car trip to the destination departs when home ends, at {@code departure_s}.
	 */
	private static CarTrip tripTo(Activity destination, double departure_s, boolean terminal,
			List<PlanElement> elements) {
		Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId("driver"));
		Plan plan = PopulationUtils.createPlan(person);
		Activity home = PopulationUtils.createActivityFromCoord("home", new Coord(0, 0));
		home.setEndTime(departure_s);
		plan.addActivity(home);
		plan.addLeg(PopulationUtils.createLeg(TransportMode.car));
		plan.addActivity(destination);
		if (!terminal) {
			plan.addLeg(PopulationUtils.createLeg(TransportMode.car));
			plan.addActivity(PopulationUtils.createActivityFromCoord("home", new Coord(0, 0)));
		}
		person.addPlan(plan);
		person.setSelectedPlan(plan);
		DiscreteModeChoiceTrip trip = new DiscreteModeChoiceTrip(home, destination, TransportMode.car, List.of(), 0, 0, 0,
				new AttributesImpl());
		trip.setDepartureTime(departure_s);
		return new CarTrip(person, plan, trip, elements);
	}

	private static CarTrip tripTo(Activity destination, double departure_s, boolean terminal) {
		return tripTo(destination, departure_s, terminal, List.of(carLeg()));
	}

	/** One routed car leg of {@value #CAR_DISTANCE_M} m and {@value #CAR_TRAVEL_TIME_S} s. */
	private static Leg carLeg() {
		return leg(TransportMode.car, CAR_DISTANCE_M, CAR_TRAVEL_TIME_S);
	}

	private static Leg leg(String mode, double distance_m, double travelTime_s) {
		Leg leg = PopulationUtils.createLeg(mode);
		leg.setTravelTime(travelTime_s);
		Route route = RouteUtils.createGenericRouteImpl(Id.createLinkId("from"), Id.createLinkId("to"));
		route.setDistance(distance_m);
		route.setTravelTime(travelTime_s);
		leg.setRoute(route);
		return leg;
	}

	private static Activity inZone(Activity activity, String zoneId) {
		activity.getAttributes().putAttribute(ZoneParkingCarCostModel.ZONE_ATTRIBUTE, zoneId);
		return activity;
	}

	private double cost_EUR(CarTrip carTrip) {
		return model.calculateCost_MU(carTrip.person(), carTrip.trip(), carTrip.elements());
	}

	/** Counts of a snapshot with exactly the given outcome counted once and every other outcome 0. */
	private static Map<ParkingOutcome, Long> onlyOnce(ParkingOutcome outcome) {
		Map<ParkingOutcome, Long> expected = new EnumMap<>(ParkingOutcome.class);
		for (ParkingOutcome value : ParkingOutcome.values()) {
			expected.put(value, value == outcome ? 1L : 0L);
		}
		return expected;
	}

	private static long total(Map<ParkingOutcome, Long> counts) {
		return counts.values().stream().mapToLong(Long::longValue).sum();
	}

	/**
	 * NO_ZONE precedes HOME (rule order of design section 3.2): a home activity outside every zone is counted as
	 * NO_ZONE, like the Python reference braunschweig.parking.cost.parking_cost_cents with tariff None.
	 */
	@Test
	public void aHomeActivityOutsideEveryZoneCountsNoZoneNotHome() {
		CarTrip carTrip = tripTo(activity("home", 61200.0), 27000, false);
		assertEquals(DRIVING_COST_EUR, cost_EUR(carTrip), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.NO_ZONE), counter.snapshotAndReset());
	}

	@Test
	public void noZoneAttributeCostsTheDrivingOnlyAndCountsNoZone() {
		CarTrip carTrip = tripTo(activity("work", 61200.0), 27000, false);
		assertEquals(DRIVING_COST_EUR, cost_EUR(carTrip), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.NO_ZONE), counter.snapshotAndReset());
	}

	/** Zone Ib, 08:00-17:00: fee hours 09:00-17:00 = 480 min at 180 ct/h = 1440 ct, capped at the 900 ct day cap. */
	@Test
	public void paidWorkStayAddsTheCappedMeteredPrice() {
		Activity work = inZone(activity("work", 61200.0), "fx_bs_ib");
		CarTrip carTrip = tripTo(work, 27000, false);
		int attributesBefore = work.getAttributes().size();
		assertEquals(DRIVING_COST_EUR + 9.00, cost_EUR(carTrip), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.PAID_METERED), counter.snapshotAndReset());
		// Pricing reads the plan, it never changes it.
		assertEquals(61200.0, work.getEndTime().seconds(), 0.0);
		assertEquals(attributesBefore, work.getAttributes().size());
		assertEquals(5, carTrip.plan().getPlanElements().size());
		assertEquals(27000.0, carTrip.trip().getDepartureTime(), 0.0);
	}

	@Test
	public void employerProvidedParkingCostsTheDrivingOnly() {
		Activity work = inZone(activity("work", 61200.0), "fx_bs_ib");
		work.getAttributes().putAttribute(ZoneParkingCarCostModel.FREE_ATTRIBUTE, true);
		assertEquals(DRIVING_COST_EUR, cost_EUR(tripTo(work, 27000, false)), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.EMPLOYER_FREE), counter.snapshotAndReset());
	}

	/** Resident zone A, work 08:00-17:00: 540 min exceed the 120 min maximum stay, except for residents of the zone. */
	@Test
	public void residentsOfTheZoneParkFreeAndOthersPayTheLongStayProduct() {
		CarTrip resident = tripTo(inZone(activity("work", 61200.0), "fx_res_a"), 27000, false);
		resident.person().getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, "fx_res_a");
		assertEquals(DRIVING_COST_EUR, cost_EUR(resident), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.RESIDENT_FREE), counter.snapshotAndReset());

		CarTrip visitor = tripTo(inZone(activity("work", 61200.0), "fx_res_a"), 27000, false);
		assertEquals(DRIVING_COST_EUR + 9.00, cost_EUR(visitor), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.PAID_LONG_STAY), counter.snapshotAndReset());

		// A resident of another zone is a visitor here (assumption R1: the exemption holds in the own zone only). The
		// fixture has one resident zone, so a street zone id stands in for the other one; ParkingPopulationCheck would
		// reject it at startup, the cost model compares the ids only.
		CarTrip otherResident = tripTo(inZone(activity("work", 61200.0), "fx_res_a"), 27000, false);
		otherResident.person().getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, "fx_bs_ia");
		assertEquals(DRIVING_COST_EUR + 9.00, cost_EUR(otherResident), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.PAID_LONG_STAY), counter.snapshotAndReset());
	}

	/** Terminal rule T1: arriving 19:30 at the last activity, the car pays until the fee window ends at 20:00. */
	@Test
	public void terminalStayPaysUntilTheFeeWindowOfTheArrivalDayEnds() {
		CarTrip carTrip = tripTo(inZone(activity("shop", null), "fx_bs_ia"), 68400, true);
		assertEquals(DRIVING_COST_EUR + 0.90, cost_EUR(carTrip), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.PAID_METERED), counter.snapshotAndReset());
	}

	/** The destination's activity type is the purpose: home is free in every zone (assumption H1). */
	@Test
	public void homeInAPaidZoneCostsTheDrivingOnly() {
		CarTrip carTrip = tripTo(inZone(activity("home", 61200.0), "fx_bs_ib"), 27000, false);
		assertEquals(DRIVING_COST_EUR, cost_EUR(carTrip), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.HOME), counter.snapshotAndReset());
	}

	/**
	 * The stay starts when the whole trip ends, after the egress walk, and only car legs are driving distance: departure
	 * 09:00 + 5 min walk + 30 min car + 5 min walk = arrival 09:40; zone Ia until 11:00 = 80 min at 3 ct/min = 240 ct
	 * (an arrival at the end of the car leg, 09:35, would cost 255 ct).
	 */
	@Test
	public void theStayStartsAfterTheEgressWalkAndWalkingIsNotDrivingDistance() {
		List<PlanElement> elements = new ArrayList<>();
		elements.add(leg(TransportMode.walk, 400.0, 300.0));
		elements.add(PopulationUtils.createStageActivityFromCoordLinkIdAndModePrefix(new Coord(0, 0),
				Id.createLinkId("from"), TransportMode.car));
		elements.add(carLeg());
		elements.add(PopulationUtils.createStageActivityFromCoordLinkIdAndModePrefix(new Coord(1000, 0),
				Id.createLinkId("to"), TransportMode.car));
		elements.add(leg(TransportMode.walk, 400.0, 300.0));
		CarTrip carTrip = tripTo(inZone(activity("shop", 39600.0), "fx_bs_ia"), 32400, false, elements);
		assertEquals(DRIVING_COST_EUR + 2.40, cost_EUR(carTrip), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.PAID_METERED), counter.snapshotAndReset());
	}

	@Test
	public void unknownZoneIdFails() {
		CarTrip carTrip = tripTo(inZone(activity("work", 61200.0), "bs_zone_unknown"), 27000, false);
		IllegalStateException error = assertThrows(IllegalStateException.class, () -> cost_EUR(carTrip));
		assertTrue(error.getMessage(), error.getMessage().contains("bs_zone_unknown"));
		assertTrue(error.getMessage(), error.getMessage().contains(TARIFFS_PATH));
		assertEquals(0L, total(counter.snapshotAndReset()));
	}

	/** A resident zone id is a zone id of the same release: an unknown one fails instead of never exempting anyone. */
	@Test
	public void unknownResidentZoneIdFails() {
		CarTrip carTrip = tripTo(inZone(activity("work", 61200.0), "fx_res_a"), 27000, false);
		carTrip.person().getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, "bs_res_unknown");
		IllegalStateException error = assertThrows(IllegalStateException.class, () -> cost_EUR(carTrip));
		assertTrue(error.getMessage(), error.getMessage().contains("bs_res_unknown"));
		assertTrue(error.getMessage(), error.getMessage().contains(TARIFFS_PATH));
	}

	/** The plans writer types the attributes (String zone ids, Boolean parkingFree); another type is a writer defect. */
	@Test
	public void mistypedAttributesFailNamingTheAttribute() {
		CarTrip numericZone = tripTo(activity("work", 61200.0), 27000, false);
		numericZone.trip().getDestinationActivity().getAttributes().putAttribute(ZoneParkingCarCostModel.ZONE_ATTRIBUTE,
				42);
		IllegalStateException zoneError = assertThrows(IllegalStateException.class, () -> cost_EUR(numericZone));
		assertTrue(zoneError.getMessage(), zoneError.getMessage().contains(ZoneParkingCarCostModel.ZONE_ATTRIBUTE));

		CarTrip textualFree = tripTo(inZone(activity("work", 61200.0), "fx_bs_ib"), 27000, false);
		textualFree.trip().getDestinationActivity().getAttributes().putAttribute(ZoneParkingCarCostModel.FREE_ATTRIBUTE,
				"true");
		IllegalStateException freeError = assertThrows(IllegalStateException.class, () -> cost_EUR(textualFree));
		assertTrue(freeError.getMessage(), freeError.getMessage().contains(ZoneParkingCarCostModel.FREE_ATTRIBUTE));
		assertTrue(freeError.getMessage(), freeError.getMessage().contains("java.lang.String"));

		CarTrip numericResident = tripTo(inZone(activity("work", 61200.0), "fx_res_a"), 27000, false);
		numericResident.person().getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, 7);
		IllegalStateException residentError = assertThrows(IllegalStateException.class, () -> cost_EUR(numericResident));
		assertTrue(residentError.getMessage(),
				residentError.getMessage().contains(ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE));
		assertEquals(0L, total(counter.snapshotAndReset()));
	}

	/**
	 * departure >= arrival by construction: under the eqasim time interpretation (shiftActivityEndTimes) an activity
	 * whose planned end has passed when the car arrives ends at the arrival, so the stay has length 0. The car arrives
	 * 10:15 at a shop planned to end at 10:00 in zone Ia: nothing is chargeable.
	 */
	@Test
	public void aLateArrivalIsAStayOfLengthZero() {
		CarTrip carTrip = tripTo(inZone(activity("shop", 36000.0), "fx_bs_ia"), 35100, false);
		assertEquals(DRIVING_COST_EUR, cost_EUR(carTrip), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.OUTSIDE_FEE_HOURS), counter.snapshotAndReset());
	}

	/**
	 * A time interpretation that lets an activity end before the car arrives (the MATSim default ignoreDelays) breaks the
	 * departure >= arrival guarantee; the model fails loudly and names the setting instead of pricing a negative stay.
	 */
	@Test
	public void anActivityEndingBeforeTheCarArrivesFailsLoudly() throws Exception {
		ZoneParkingCarCostModel ignoringDelays = model(TimeInterpretation
				.create(ActivityDurationInterpretation.tryEndTimeThenDuration, TripDurationHandling.ignoreDelays), counter);
		CarTrip carTrip = tripTo(inZone(activity("shop", 36000.0), "fx_bs_ia"), 35100, false);
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> ignoringDelays.calculateCost_MU(carTrip.person(), carTrip.trip(), carTrip.elements()));
		assertTrue(error.getMessage(), error.getMessage().contains("driver"));
		assertTrue(error.getMessage(), error.getMessage().contains("tripDurationHandling"));
		assertEquals(0L, total(counter.snapshotAndReset()));
	}

	/** A non-terminal activity without end time and duration has no departure: a plan defect, not a free stay. */
	@Test
	public void aNonTerminalActivityWithoutEndFails() {
		CarTrip carTrip = tripTo(inZone(activity("shop", null), "fx_bs_ia"), 35100, false);
		IllegalStateException error = assertThrows(IllegalStateException.class, () -> cost_EUR(carTrip));
		assertTrue(error.getMessage(), error.getMessage().contains("driver"));
		assertTrue(error.getMessage(), error.getMessage().contains("shop"));
	}

	/**
	 * A routed element without a defined end (a car leg without travel time, in the route or on the leg) leaves the stay
	 * without an arrival. MATSim's TimeTracker fails with a generic message when a further element follows it; the model
	 * names the person, the index and type of the element and the car trip instead, whether the element is the last one
	 * of the trip or not.
	 */
	@Test
	public void anElementWithoutADefinedEndFailsNamingTheElementAndTheTrip() {
		Leg untimedCarLeg = PopulationUtils.createLeg(TransportMode.car);
		Route route = RouteUtils.createGenericRouteImpl(Id.createLinkId("from"), Id.createLinkId("to"));
		route.setDistance(CAR_DISTANCE_M);
		untimedCarLeg.setRoute(route);

		List<PlanElement> elements = new ArrayList<>();
		elements.add(leg(TransportMode.walk, 400.0, 300.0));
		elements.add(PopulationUtils.createStageActivityFromCoordLinkIdAndModePrefix(new Coord(0, 0),
				Id.createLinkId("from"), TransportMode.car));
		elements.add(untimedCarLeg);
		elements.add(PopulationUtils.createStageActivityFromCoordLinkIdAndModePrefix(new Coord(1000, 0),
				Id.createLinkId("to"), TransportMode.car));
		elements.add(leg(TransportMode.walk, 400.0, 300.0));
		CarTrip midTrip = tripTo(inZone(activity("shop", 39600.0), "fx_bs_ia"), 32400, false, elements);
		IllegalStateException error = assertThrows(IllegalStateException.class, () -> cost_EUR(midTrip));
		assertTrue(error.getMessage(), error.getMessage().startsWith("person driver: element 2 (leg car) of the car trip"
				+ " at trip index 0 (home to shop, departing at 32400.0 s) has no defined end time"));

		CarTrip lastElement = tripTo(inZone(activity("shop", 39600.0), "fx_bs_ia"), 32400, false, List.of(untimedCarLeg));
		error = assertThrows(IllegalStateException.class, () -> cost_EUR(lastElement));
		assertTrue(error.getMessage(), error.getMessage().startsWith("person driver: element 0 (leg car) of the car trip"
				+ " at trip index 0 (home to shop, departing at 32400.0 s) has no defined end time"));
		assertEquals(0L, total(counter.snapshotAndReset()));
	}

	/** Every priced call records exactly one outcome, so the report shows the outcome mix of all evaluated car trips. */
	@Test
	public void everyCallRecordsExactlyOneOutcome() {
		CarTrip free = tripTo(activity("leisure", 61200.0), 27000, false);
		CarTrip paid = tripTo(inZone(activity("work", 61200.0), "fx_bs_ib"), 27000, false);
		for (int call = 0; call < 3; call++) {
			cost_EUR(free);
		}
		cost_EUR(paid);
		cost_EUR(paid);
		Map<ParkingOutcome, Long> counts = counter.snapshotAndReset();
		assertEquals(Long.valueOf(3), counts.get(ParkingOutcome.NO_ZONE));
		assertEquals(Long.valueOf(2), counts.get(ParkingOutcome.PAID_METERED));
		assertEquals(5L, total(counts));
	}
}
