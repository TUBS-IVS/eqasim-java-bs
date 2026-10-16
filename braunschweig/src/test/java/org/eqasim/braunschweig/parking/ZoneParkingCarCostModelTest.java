package org.eqasim.braunschweig.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

	/** The model of a config without minimumStayMinutes: no minimum parked duration, version 1 pricing. */
	private static ZoneParkingCarCostModel model(TimeInterpretation timeInterpretation, ParkingOutcomeCounter counter)
			throws Exception {
		return model(timeInterpretation, counter, null);
	}

	/**
	 * The model of an enabled module with the fixture tariff model and the parameter minimumStayMinutes as the preparation
	 * stage writes it, or without that parameter when {@code minimumStayMinutes} is null.
	 */
	private static ZoneParkingCarCostModel model(TimeInterpretation timeInterpretation, ParkingOutcomeCounter counter,
			String minimumStayMinutes) throws Exception {
		BraunschweigCostParameters costParameters = new BraunschweigCostParameters();
		costParameters.carCost_EUR_km = CAR_COST_EUR_KM;
		ParkingConfigGroup parking = new ParkingConfigGroup();
		parking.setEnabled("true");
		parking.setTariffsPath(TARIFFS_PATH);
		if (minimumStayMinutes != null) {
			parking.addParam("minimumStayMinutes", minimumStayMinutes);
		}
		return new ZoneParkingCarCostModel(costParameters, timeInterpretation, fixtureModel(0.0), counter, parking);
	}

	/**
	 * The fixture tariff model with the given option price coefficient. The golden cases E01..E38 that this test pins through
	 * the cost model are priced at beta 0 (the price-independent weights of v2), while the fixture states the coefficient of the
	 * mode choice, 0.310998: the models of this test use 0 unless a test is about the price in the weights.
	 */
	private static ParkingTariffs fixtureModel(double optionPriceBetaPerEuro) throws Exception {
		ObjectNode tree = (ObjectNode) new ObjectMapper().readTree(fixturePath().toFile());
		tree.put("option_price_beta_u_per_eur", optionPriceBetaPerEuro);
		return ParkingTariffs.parse(tree);
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

	/** The counts since the last snapshot summed per outcome over zones and purposes; the counter restarts from 0. */
	private Map<ParkingOutcome, Long> countsByOutcome() {
		return ParkingOutcomeCounter.countsByOutcome(counter.snapshotAndReset());
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
		assertEquals(onlyOnce(ParkingOutcome.NO_ZONE), countsByOutcome());
	}

	@Test
	public void noZoneAttributeCostsTheDrivingOnlyAndCountsNoZone() {
		CarTrip carTrip = tripTo(activity("work", 61200.0), 27000, false);
		assertEquals(DRIVING_COST_EUR, cost_EUR(carTrip), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.NO_ZONE), countsByOutcome());
	}

	/** Zone Ib, 08:00-17:00: fee hours 09:00-17:00 = 480 min at 180 ct/h = 1440 ct, capped at the 900 ct day cap. */
	@Test
	public void paidWorkStayAddsTheCappedMeteredPrice() {
		Activity work = inZone(activity("work", 61200.0), "fx_bs_ib");
		CarTrip carTrip = tripTo(work, 27000, false);
		int attributesBefore = work.getAttributes().size();
		assertEquals(DRIVING_COST_EUR + 9.00, cost_EUR(carTrip), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.PAID_METERED), countsByOutcome());
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
		assertEquals(onlyOnce(ParkingOutcome.EMPLOYER_FREE), countsByOutcome());
	}

	/** Resident zone A, work 08:00-17:00: 540 min exceed the 120 min maximum stay, except for residents of the zone. */
	@Test
	public void residentsOfTheZoneParkFreeAndOthersPayTheLongStayProduct() {
		CarTrip resident = tripTo(inZone(activity("work", 61200.0), "fx_res_a"), 27000, false);
		resident.person().getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, "fx_res_a");
		assertEquals(DRIVING_COST_EUR, cost_EUR(resident), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.RESIDENT_FREE), countsByOutcome());

		CarTrip visitor = tripTo(inZone(activity("work", 61200.0), "fx_res_a"), 27000, false);
		assertEquals(DRIVING_COST_EUR + 9.00, cost_EUR(visitor), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.PAID_LONG_STAY), countsByOutcome());

		// A resident of another zone is a visitor here (assumption R1: the exemption holds in the own zone only). The
		// fixture has one resident zone, so a street zone id stands in for the other one; ParkingPopulationCheck would
		// reject it at startup, the cost model compares the ids only.
		CarTrip otherResident = tripTo(inZone(activity("work", 61200.0), "fx_res_a"), 27000, false);
		otherResident.person().getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, "fx_bs_ia");
		assertEquals(DRIVING_COST_EUR + 9.00, cost_EUR(otherResident), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.PAID_LONG_STAY), countsByOutcome());
	}

	/** Terminal rule T1: arriving 19:30 at the last activity, the car pays until the fee window ends at 20:00. */
	@Test
	public void terminalStayPaysUntilTheFeeWindowOfTheArrivalDayEnds() {
		CarTrip carTrip = tripTo(inZone(activity("shop", null), "fx_bs_ia"), 68400, true);
		assertEquals(DRIVING_COST_EUR + 0.90, cost_EUR(carTrip), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.PAID_METERED), countsByOutcome());
	}

	/** The destination's activity type is the purpose: home is free in every zone (assumption H1). */
	@Test
	public void homeInAPaidZoneCostsTheDrivingOnly() {
		CarTrip carTrip = tripTo(inZone(activity("home", 61200.0), "fx_bs_ib"), 27000, false);
		assertEquals(DRIVING_COST_EUR, cost_EUR(carTrip), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.HOME), countsByOutcome());
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
		assertEquals(onlyOnce(ParkingOutcome.PAID_METERED), countsByOutcome());
	}

	@Test
	public void unknownZoneIdFails() {
		CarTrip carTrip = tripTo(inZone(activity("work", 61200.0), "bs_zone_unknown"), 27000, false);
		IllegalStateException error = assertThrows(IllegalStateException.class, () -> cost_EUR(carTrip));
		assertTrue(error.getMessage(), error.getMessage().contains("bs_zone_unknown"));
		assertTrue(error.getMessage(), error.getMessage().contains(TARIFFS_PATH));
		assertEquals(0L, total(countsByOutcome()));
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
		assertEquals(0L, total(countsByOutcome()));
	}

	/**
	 * departure >= arrival by construction: under the eqasim time interpretation (shiftActivityEndTimes) an activity
	 * whose planned end has passed when the car arrives ends at the arrival, so the stay has length 0. The car arrives
	 * 10:15 at a shop planned to end at 10:00 in zone Ia: without a minimum parked duration (version 1) nothing is
	 * chargeable.
	 */
	@Test
	public void aLateArrivalIsAStayOfLengthZero() {
		CarTrip carTrip = tripTo(inZone(activity("shop", 36000.0), "fx_bs_ia"), 35100, false);
		assertEquals(DRIVING_COST_EUR, cost_EUR(carTrip), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.OUTSIDE_FEE_HOURS), countsByOutcome());
		assertEquals(0L, counter.snapshotAndResetMinimumStayExtensions());
	}

	/**
	 * Assumption L1 with a minimum parked duration of 15 min: the zero-length stay of the late arrival above is priced
	 * 10:15 to 10:30, 15 min at 3 ct/min = 45 ct. Only the price changes: the plan keeps its times.
	 */
	@Test
	public void aLateArrivalIsPricedForTheMinimumStay() throws Exception {
		ZoneParkingCarCostModel withMinimumStay = model(eqasimTimeInterpretation(), counter, "15");
		Activity shop = inZone(activity("shop", 36000.0), "fx_bs_ia");
		CarTrip carTrip = tripTo(shop, 35100, false);
		assertEquals(DRIVING_COST_EUR + 0.45,
				withMinimumStay.calculateCost_MU(carTrip.person(), carTrip.trip(), carTrip.elements()), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.PAID_METERED), countsByOutcome());
		assertEquals(36000.0, shop.getEndTime().seconds(), 0.0);
		assertEquals(35100.0, carTrip.trip().getDepartureTime(), 0.0);
	}

	/**
	 * The counter counts the priced stays the minimum parked duration extended, terminal stays included (after the
	 * terminal-stay rule T1): the late arrival and a terminal arrival at 19:55, which T1 ends at 20:00 and the minimum at
	 * 20:10 (still 5 chargeable minutes, 15 ct). A longer stay is not extended, and a stay outside every zone is never
	 * priced, however short. Without the parameter (version 1) the same trips extend nothing.
	 */
	@Test
	public void theCounterCountsThePricedStaysTheMinimumStayExtended() throws Exception {
		ZoneParkingCarCostModel withMinimumStay = model(eqasimTimeInterpretation(), counter, "15");
		CarTrip lateArrival = tripTo(inZone(activity("shop", 36000.0), "fx_bs_ia"), 35100, false);
		CarTrip terminalBeforeTheFeeEnd = tripTo(inZone(activity("shop", null), "fx_bs_ia"), 69900, true);
		CarTrip longerStay = tripTo(inZone(activity("work", 61200.0), "fx_bs_ib"), 27000, false);
		CarTrip lateArrivalOutsideEveryZone = tripTo(activity("shop", 36000.0), 35100, false);
		List<CarTrip> carTrips = List.of(lateArrival, terminalBeforeTheFeeEnd, longerStay, lateArrivalOutsideEveryZone);

		List<Double> costs_EUR = new ArrayList<>();
		for (CarTrip carTrip : carTrips) {
			costs_EUR.add(withMinimumStay.calculateCost_MU(carTrip.person(), carTrip.trip(), carTrip.elements()));
		}
		assertEquals(DRIVING_COST_EUR + 0.15, costs_EUR.get(1), EUR_TOLERANCE);
		assertEquals(2L, counter.snapshotAndResetMinimumStayExtensions());
		Map<ParkingOutcome, Long> counts = countsByOutcome();
		assertEquals(Long.valueOf(3), counts.get(ParkingOutcome.PAID_METERED));
		assertEquals(Long.valueOf(1), counts.get(ParkingOutcome.NO_ZONE));
		assertEquals(4L, total(counts));

		for (CarTrip carTrip : carTrips) {
			cost_EUR(carTrip);
		}
		assertEquals(0L, counter.snapshotAndResetMinimumStayExtensions());
		assertEquals(4L, total(countsByOutcome()));
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
		assertEquals(0L, total(countsByOutcome()));
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
		assertEquals(0L, total(countsByOutcome()));
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
		Map<ParkingOutcome, Long> counts = countsByOutcome();
		assertEquals(Long.valueOf(3), counts.get(ParkingOutcome.NO_ZONE));
		assertEquals(Long.valueOf(2), counts.get(ParkingOutcome.PAID_METERED));
		assertEquals(5L, total(counts));
	}

	/**
	 * Every call counts its outcome in the cell of the zone of the destination and the purpose of the stay (outcome CSV
	 * v2, spec amendment A5): a stay outside every zone has no zone id, and the resident rules R1 and R2 count under the
	 * zone of the stay like a priced outcome does.
	 */
	@Test
	public void everyCallCountsItsOutcomeUnderTheZoneAndThePurposeOfTheStay() {
		cost_EUR(tripTo(inZone(activity("work", 61200.0), "fx_bs_ib"), 27000, false));
		cost_EUR(tripTo(inZone(activity("work", 61200.0), "fx_bs_ib"), 27000, false));
		cost_EUR(tripTo(activity("home", 61200.0), 27000, false));
		cost_EUR(tripTo(activity("leisure", 61200.0), 27000, false));
		CarTrip resident = tripTo(inZone(activity("work", 61200.0), "fx_res_a"), 27000, false);
		resident.person().getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, "fx_res_a");
		cost_EUR(resident);
		cost_EUR(withHomeDistrict(tripTo(inDistrict(shopInZoneIa(), "fx_district_a"), 32400, false), "fx_district_a"));
		Activity freeWork = inZone(activity("work", 61200.0), "fx_bs_ib");
		freeWork.getAttributes().putAttribute(ZoneParkingCarCostModel.FREE_ATTRIBUTE, true);
		cost_EUR(tripTo(freeWork, 27000, false));

		Map<ParkingOutcomeCounter.Cell, Long> expected = new TreeMap<>();
		expected.put(new ParkingOutcomeCounter.Cell(ParkingOutcome.NO_ZONE, "", "home"), 1L);
		expected.put(new ParkingOutcomeCounter.Cell(ParkingOutcome.NO_ZONE, "", "leisure"), 1L);
		expected.put(new ParkingOutcomeCounter.Cell(ParkingOutcome.EMPLOYER_FREE, "fx_bs_ib", "work"), 1L);
		expected.put(new ParkingOutcomeCounter.Cell(ParkingOutcome.RESIDENT_FREE, "fx_bs_ia", "shop"), 1L);
		expected.put(new ParkingOutcomeCounter.Cell(ParkingOutcome.RESIDENT_FREE, "fx_res_a", "work"), 1L);
		expected.put(new ParkingOutcomeCounter.Cell(ParkingOutcome.PAID_METERED, "fx_bs_ib", "work"), 2L);
		assertEquals(expected, counter.snapshotAndReset());
	}

	/**
	 * The garage and the commuter product reach the price and the counter through the cost model (spec lever 2): in zone
	 * fx_bs_ia_v2 a shop stay 09:00-13:00 has 240 chargeable minutes above the street maximum stay of 180 without a
	 * long-stay product, so the garage charges 4 started hours at 120 ct (golden case V01); in fx_bs_ib_v2 a work stay
	 * 08:00-17:00 takes the commuter product 376 ct (V04) and a leisure stay the street product capped at 900 ct (V05).
	 * The car trips depart 30 min before the stay starts.
	 */
	@Test
	public void theGarageAndTheCommuterProductPriceAndCountThroughTheCostModel() {
		CarTrip garage = tripTo(inZone(activity("shop", 46800.0), "fx_bs_ia_v2"), 30600, false);
		assertEquals(DRIVING_COST_EUR + 4.80, cost_EUR(garage), EUR_TOLERANCE);
		CarTrip commuter = tripTo(inZone(activity("work", 61200.0), "fx_bs_ib_v2"), 27000, false);
		assertEquals(DRIVING_COST_EUR + 3.76, cost_EUR(commuter), EUR_TOLERANCE);
		CarTrip leisure = tripTo(inZone(activity("leisure", 61200.0), "fx_bs_ib_v2"), 27000, false);
		assertEquals(DRIVING_COST_EUR + 9.00, cost_EUR(leisure), EUR_TOLERANCE);

		Map<ParkingOutcomeCounter.Cell, Long> expected = new TreeMap<>();
		expected.put(new ParkingOutcomeCounter.Cell(ParkingOutcome.PAID_METERED, "fx_bs_ib_v2", "leisure"), 1L);
		expected.put(new ParkingOutcomeCounter.Cell(ParkingOutcome.PAID_GARAGE, "fx_bs_ia_v2", "shop"), 1L);
		expected.put(new ParkingOutcomeCounter.Cell(ParkingOutcome.PAID_COMMUTER, "fx_bs_ib_v2", "work"), 1L);
		assertEquals(expected, counter.snapshotAndReset());
	}

	/**
	 * Spec amendment A3 end to end (golden case V21): a terminal stay arriving 19:50 in fx_capped_street_v2 departs by the
	 * terminal-stay rule T1 at the end of the STREET fee window (20:00), the minimum parked duration of 15 min extends it
	 * to 20:10, and every product prices that one interval: the street 10 chargeable minutes (30 ct), the uncapped all-day
	 * garage 15 min at 1 ct/min (15 ct). Without the minimum the garage would charge 10 min (10 ct): the minimum applies
	 * after T1, and T1 never takes the garage window (which would end the stay at 24:00 and charge 250 ct).
	 */
	@Test
	public void aTerminalStayIsExtendedByTheMinimumStayAfterTheTerminalRuleAndPricedByEveryProduct() throws Exception {
		ZoneParkingCarCostModel withMinimumStay = model(eqasimTimeInterpretation(), counter, "15");
		CarTrip terminal = tripTo(inZone(activity("shop", null), "fx_capped_street_v2"), 69600, true);
		assertEquals(DRIVING_COST_EUR + 0.15,
				withMinimumStay.calculateCost_MU(terminal.person(), terminal.trip(), terminal.elements()), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.PAID_GARAGE), countsByOutcome());
		assertEquals(1L, counter.snapshotAndResetMinimumStayExtensions());

		assertEquals(DRIVING_COST_EUR + 0.10, cost_EUR(terminal), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.PAID_GARAGE), countsByOutcome());
		assertEquals(0L, counter.snapshotAndResetMinimumStayExtensions());
	}

	/** Activity attribute of the plans writer (spec Amendment C3): the resident parking district the activity lies in. */
	private static Activity inDistrict(Activity activity, String districtId) {
		activity.getAttributes().putAttribute(ZoneParkingCarCostModel.DISTRICT_ATTRIBUTE, districtId);
		return activity;
	}

	/** Person attribute of the plans writer (spec Amendment C3): the resident parking district of the person's home. */
	private static CarTrip withHomeDistrict(CarTrip carTrip, String districtId) {
		carTrip.person().getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_DISTRICT_ATTRIBUTE, districtId);
		return carTrip;
	}

	/**
	 * A shop stay in the street zone fx_bs_ia: the car arrives 09:30 and the activity ends 11:00, 90 min at 3 ct/min =
	 * 270 ct without rule R2. Zone fx_bs_ia honours resident permits.
	 */
	private static Activity shopInZoneIa() {
		return inZone(activity("shop", 39600.0), "fx_bs_ia");
	}

	/**
	 * Rule R2 (spec Amendment C3, ASSUMPTION R2-a): the calculator takes a flag, the cost model derives it from the plan
	 * attributes. A stay in the district of the person's home (both attributes set and equal) is free, whatever the zone
	 * type: fx_bs_ia is a street zone that exempts no resident.
	 */
	@Test
	public void aStayInTheDistrictOfThePersonsHomeIsFree() {
		CarTrip carTrip = withHomeDistrict(tripTo(inDistrict(shopInZoneIa(), "fx_district_a"), 32400, false), "fx_district_a");
		assertEquals(DRIVING_COST_EUR, cost_EUR(carTrip), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.RESIDENT_FREE), countsByOutcome());
	}

	/**
	 * The flag is false, so the stay is priced as without the rule, when the districts differ (a stay in another district),
	 * when the activity lies in no district, and when the person has no home district. The calculator receives a boolean
	 * only, so the golden cases cannot tell these apart; they are pinned here.
	 */
	@Test
	public void aStayInAnotherDistrictOrInNoDistrictOrOfAPersonWithoutAHomeDistrictIsPriced() {
		CarTrip otherDistrict = withHomeDistrict(tripTo(inDistrict(shopInZoneIa(), "fx_district_b"), 32400, false),
				"fx_district_a");
		assertEquals(DRIVING_COST_EUR + 2.70, cost_EUR(otherDistrict), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.PAID_METERED), countsByOutcome());

		CarTrip noActivityDistrict = withHomeDistrict(tripTo(shopInZoneIa(), 32400, false), "fx_district_a");
		assertEquals(DRIVING_COST_EUR + 2.70, cost_EUR(noActivityDistrict), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.PAID_METERED), countsByOutcome());

		CarTrip noHomeDistrict = tripTo(inDistrict(shopInZoneIa(), "fx_district_a"), 32400, false);
		assertEquals(DRIVING_COST_EUR + 2.70, cost_EUR(noHomeDistrict), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.PAID_METERED), countsByOutcome());

		// Neither attribute set: the pricing of version 1.
		assertEquals(DRIVING_COST_EUR + 2.70, cost_EUR(tripTo(shopInZoneIa(), 32400, false)), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.PAID_METERED), countsByOutcome());
	}

	/**
	 * The district rule needs a zone that honours resident permits (ASSUMPTION R2-a): the separately operated car park
	 * fx_bga_v2 does not, so the same stay in the own district is priced there; a campus never honours them.
	 */
	@Test
	public void theDistrictRuleDoesNotFreeAStayInAZoneWithoutResidentPermits() {
		Activity shopInCarPark = inDistrict(inZone(activity("shop", 39600.0), "fx_bga_v2"), "fx_district_a");
		CarTrip carPark = withHomeDistrict(tripTo(shopInCarPark, 32400, false), "fx_district_a");
		assertEquals(DRIVING_COST_EUR + 2.70, cost_EUR(carPark), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.PAID_METERED), countsByOutcome());

		Activity workOnCampus = inDistrict(inZone(activity("work", 61200.0), "fx_campus"), "fx_district_a");
		CarTrip campus = withHomeDistrict(tripTo(workOnCampus, 27000, false), "fx_district_a");
		assertEquals(DRIVING_COST_EUR + 3.50, cost_EUR(campus), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.PAID_CAMPUS_MEMBER), countsByOutcome());
	}

	/**
	 * Z1 first: the district rule acts inside a fee zone only, so a stay outside every zone stays NO_ZONE in the own
	 * district; the home purpose and the employer's free parking come before the district rule.
	 */
	@Test
	public void theDistrictRuleActsAfterNoZoneHomeAndEmployerFree() {
		Activity shopOutsideEveryZone = inDistrict(activity("shop", 39600.0), "fx_district_a");
		CarTrip noZone = withHomeDistrict(tripTo(shopOutsideEveryZone, 32400, false), "fx_district_a");
		assertEquals(DRIVING_COST_EUR, cost_EUR(noZone), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.NO_ZONE), countsByOutcome());

		Activity homeInZone = inDistrict(inZone(activity("home", 61200.0), "fx_bs_ia"), "fx_district_a");
		assertEquals(DRIVING_COST_EUR, cost_EUR(withHomeDistrict(tripTo(homeInZone, 27000, false), "fx_district_a")),
				EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.HOME), countsByOutcome());

		Activity freeWork = inDistrict(inZone(activity("work", 61200.0), "fx_bs_ib"), "fx_district_a");
		freeWork.getAttributes().putAttribute(ZoneParkingCarCostModel.FREE_ATTRIBUTE, true);
		assertEquals(DRIVING_COST_EUR, cost_EUR(withHomeDistrict(tripTo(freeWork, 27000, false), "fx_district_a")),
				EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.EMPLOYER_FREE), countsByOutcome());
	}

	/** A district id is an id of the same release as the tariff model: an unknown one fails instead of never matching. */
	@Test
	public void anUnknownDistrictIdFailsNamingThePersonTheAttributeAndTheTariffs() {
		CarTrip unknownActivityDistrict = withHomeDistrict(
				tripTo(inDistrict(shopInZoneIa(), "bs_district_unknown"), 32400, false), "fx_district_a");
		IllegalStateException activityError = assertThrows(IllegalStateException.class,
				() -> cost_EUR(unknownActivityDistrict));
		// The message names the person, the plan and the activity as the startup check of the plans does.
		assertEquals("person driver, plan 0, activity 1 (shop): parkingDistrict bs_district_unknown is not a resident district"
				+ " of the parking tariff model " + TARIFFS_PATH + " (braunschweigParking.tariffsPath); the tariff model and the"
				+ " plans must come from the same release", activityError.getMessage());

		CarTrip unknownHomeDistrict = withHomeDistrict(tripTo(shopInZoneIa(), 32400, false), "bs_district_unknown");
		IllegalStateException personError = assertThrows(IllegalStateException.class, () -> cost_EUR(unknownHomeDistrict));
		assertEquals("person driver: residentParkingDistrict bs_district_unknown is not a resident district of the parking tariff"
				+ " model " + TARIFFS_PATH + " (braunschweigParking.tariffsPath); the tariff model and the plans must come from"
				+ " the same release", personError.getMessage());
		assertEquals(0L, total(countsByOutcome()));
	}

	/** The plans writer types the district attributes (String ids); another type is a writer defect. */
	@Test
	public void mistypedDistrictAttributesFailNamingTheAttribute() {
		Activity numericDistrict = shopInZoneIa();
		numericDistrict.getAttributes().putAttribute(ZoneParkingCarCostModel.DISTRICT_ATTRIBUTE, 3);
		IllegalStateException activityError = assertThrows(IllegalStateException.class,
				() -> cost_EUR(tripTo(numericDistrict, 32400, false)));
		assertTrue(activityError.getMessage(), activityError.getMessage().contains(ZoneParkingCarCostModel.DISTRICT_ATTRIBUTE));
		assertTrue(activityError.getMessage(), activityError.getMessage().contains("java.lang.Integer"));

		CarTrip numericHomeDistrict = tripTo(shopInZoneIa(), 32400, false);
		numericHomeDistrict.person().getAttributes().putAttribute(ZoneParkingCarCostModel.RESIDENT_DISTRICT_ATTRIBUTE, 3);
		IllegalStateException personError = assertThrows(IllegalStateException.class, () -> cost_EUR(numericHomeDistrict));
		assertTrue(personError.getMessage(),
				personError.getMessage().contains(ZoneParkingCarCostModel.RESIDENT_DISTRICT_ATTRIBUTE));
		assertEquals(0L, total(countsByOutcome()));
	}

	/**
	 * The model prices every car stay, so its happy path builds no name for an error message: a priced stay that reads all
	 * five attributes never asks the person for its id or for all of its plans, which only a message needs.
	 */
	@Test
	public void aPricedStayBuildsNoNamesForErrorMessages() {
		Activity work = inDistrict(inZone(activity("work", 61200.0), "fx_bs_ib"), "fx_district_a");
		work.getAttributes().putAttribute(ZoneParkingCarCostModel.FREE_ATTRIBUTE, false);
		CarTrip carTrip = withHomeDistrict(tripTo(work, 27000, false), "fx_district_b");
		Person real = carTrip.person();
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

		assertEquals(DRIVING_COST_EUR + 9.00, model.calculateCost_MU(counting, carTrip.trip(), carTrip.elements()),
				EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.PAID_METERED), countsByOutcome());
		assertTrue("the person attributes were read: " + calls, calls.containsKey("getAttributes"));
		assertFalse(calls.toString(), calls.containsKey("getId"));
		assertFalse(calls.toString(), calls.containsKey("getPlans"));
	}

	// ----------------------------------------------------------------------------------------- garage options (schema 4)

	/** A destination activity at a coordinate in EPSG:25832, ending at {@code end_s}. */
	private static Activity activityAt(String type, double end_s, double x_m, double y_m) {
		Activity activity = PopulationUtils.createActivityFromCoord(type, new Coord(x_m, y_m));
		activity.setEndTime(end_s);
		return activity;
	}

	/** The model of the fixture tariff model with the given decay lengths and its counter. */
	private static ZoneParkingCarCostModel modelWithDecays(double decayMeters, double commuteDecayMeters,
			ParkingOutcomeCounter counter) throws Exception {
		ObjectNode tree = (ObjectNode) new ObjectMapper().readTree(fixturePath().toFile());
		tree.put("garage_decay_m", decayMeters);
		tree.put("garage_decay_commute_m", commuteDecayMeters);
		tree.put("option_price_beta_u_per_eur", 0.0);
		BraunschweigCostParameters costParameters = new BraunschweigCostParameters();
		costParameters.carCost_EUR_km = CAR_COST_EUR_KM;
		ParkingConfigGroup parking = new ParkingConfigGroup();
		parking.setEnabled("true");
		parking.setTariffsPath(TARIFFS_PATH);
		return new ZoneParkingCarCostModel(costParameters, eqasimTimeInterpretation(), ParkingTariffs.parse(tree), counter,
				parking);
	}

	/**
	 * Golden case E02 through the cost model: a shop stay 09:00-10:00 (the car arrives 1800 s after the trip departs) in zone
	 * fx_bs_ib at the coordinate of golden destination 580000/5750000, where one garage is within 1000 m. The price is the
	 * expected cost of the golden file, 186 ct, with outcome PAID_EXPECTED; the report cell carries the garage probability.
	 */
	@Test
	public void aStayWithAGarageInRangeIsPricedAsTheExpectedCostAndReportsTheGarageProbability() throws Exception {
		Activity shop = inZone(activityAt("shop", 39600.0, 580000.0, 5750000.0), "fx_bs_ib");
		CarTrip carTrip = tripTo(shop, 34200, false);

		assertEquals(DRIVING_COST_EUR + 1.86, cost_EUR(carTrip), EUR_TOLERANCE);

		var cells = counter.snapshotAndResetTotals();
		assertEquals(1, cells.size());
		var cell = cells.firstKey();
		assertEquals(new ParkingOutcomeCounter.Cell(ParkingOutcome.PAID_EXPECTED, "fx_bs_ib", "shop"), cell);
		assertEquals(1L, cells.get(cell).count());
		assertEquals(0.3208213008, cells.get(cell).garageProbabilitySum(), 1e-9);
		assertEquals(1L, cells.get(cell).staysWithGaragesInRange());
	}

	/** Golden case E01: no garage within 1000 m, so the stay is priced as before and counted as eligible without garages. */
	@Test
	public void aStayWithoutAGarageInRangeIsPricedAsBeforeAndCounted() throws Exception {
		Activity shop = inZone(activityAt("shop", 39600.0, 570000.0, 5750000.0), "fx_bs_ib");

		assertEquals(DRIVING_COST_EUR + 1.80, cost_EUR(tripTo(shop, 34200, false)), EUR_TOLERANCE);

		var cells = counter.snapshotAndResetTotals();
		var totals = cells.get(new ParkingOutcomeCounter.Cell(ParkingOutcome.PAID_METERED, "fx_bs_ib", "shop"));
		assertEquals(1L, totals.count());
		assertEquals(0.0, totals.garageProbabilitySum(), 0.0);
		assertEquals(0L, totals.staysWithGaragesInRange());
		GarageOptionCounters.Snapshot rates = counter.garageOptionCounters().snapshotAndReset();
		assertEquals(1L, rates.stays());
		assertEquals(1L, rates.eligible());
		assertEquals(0L, rates.withGaragesInRange());
	}

	/** Golden case E07: a free street pays 0 although a garage is in range (E4); the stay has garages in range, probability 0. */
	@Test
	public void aFreeStreetPaysNothingAlthoughAGarageIsInRange() throws Exception {
		Activity shop = inZone(activityAt("shop", 41100.0, 610000.0, 5750000.0), "fx_sz");

		assertEquals(DRIVING_COST_EUR, cost_EUR(tripTo(shop, 37800, false)), EUR_TOLERANCE);

		var totals = counter.snapshotAndResetTotals()
				.get(new ParkingOutcomeCounter.Cell(ParkingOutcome.FREE_WITHIN_LIMIT, "fx_sz", "shop"));
		assertEquals(1L, totals.count());
		assertEquals(0.0, totals.garageProbabilitySum(), 0.0);
		assertEquals(1L, totals.staysWithGaragesInRange());
		assertEquals(1L, counter.garageOptionCounters().snapshotAndReset().streetFree());
	}

	/** Golden case E16: a decay of 0 switches the garage options off, the same stay as E02 pays the street price of 1.80 EUR. */
	@Test
	public void aDecayOfZeroPricesWithTheZoneProductsOnly() throws Exception {
		ParkingOutcomeCounter offCounter = new ParkingOutcomeCounter();
		ZoneParkingCarCostModel off = modelWithDecays(0.0, 0.0, offCounter);
		Activity shop = inZone(activityAt("shop", 39600.0, 580000.0, 5750000.0), "fx_bs_ib");
		CarTrip carTrip = tripTo(shop, 34200, false);

		assertEquals(DRIVING_COST_EUR + 1.80, off.calculateCost_MU(carTrip.person(), carTrip.trip(), carTrip.elements()),
				EUR_TOLERANCE);

		assertEquals(onlyOnce(ParkingOutcome.PAID_METERED), ParkingOutcomeCounter.countsByOutcome(offCounter.snapshotAndReset()));
		// No option code ran: not even the stay was counted by the garage counters.
		assertEquals(0L, offCounter.garageOptionCounters().snapshot().stays());
	}

	/**
	 * Spec Amendment H through the cost model, golden cases E33, E34 and E36 of the fixture model (garage_decay_m 400,
	 * garage_decay_commute_m 250) at the same destination 580000/5750000 in zone fx_bs_ib, the same stay 08:00-16:00: a work
	 * stay is weighted with lambda_c and pays 761 ct, a shopping stay with lambda and pays 1125 ct; with lambda_c 0 the work
	 * stay is priced by the street product (900 ct, PAID_METERED) while the shopping stay keeps its expectation.
	 */
	@Test
	public void aWorkStayAndAShoppingStayAtTheSameDestinationAreWeightedWithTheirOwnDecay() throws Exception {
		Activity work = inZone(activityAt("work", 57600.0, 580000.0, 5750000.0), "fx_bs_ib");
		Activity shop = inZone(activityAt("shop", 57600.0, 580000.0, 5750000.0), "fx_bs_ib");

		assertEquals(DRIVING_COST_EUR + 7.61, cost_EUR(tripTo(work, 27000, false)), EUR_TOLERANCE);
		assertEquals(DRIVING_COST_EUR + 11.25, cost_EUR(tripTo(shop, 27000, false)), EUR_TOLERANCE);
		var cells = counter.snapshotAndResetTotals();
		assertEquals(2, cells.size());
		assertEquals(0.2314752165, cells.get(new ParkingOutcomeCounter.Cell(ParkingOutcome.PAID_EXPECTED, "fx_bs_ib", "work"))
				.garageProbabilitySum(), 1e-9);
		assertEquals(0.3208213008, cells.get(new ParkingOutcomeCounter.Cell(ParkingOutcome.PAID_EXPECTED, "fx_bs_ib", "shop"))
				.garageProbabilitySum(), 1e-9);

		ParkingOutcomeCounter commuterOffCounter = new ParkingOutcomeCounter();
		ZoneParkingCarCostModel commuterOff = modelWithDecays(400.0, 0.0, commuterOffCounter);
		CarTrip workTrip = tripTo(work, 27000, false);
		assertEquals(DRIVING_COST_EUR + 9.00, commuterOff.calculateCost_MU(workTrip.person(), workTrip.trip(),
				workTrip.elements()), EUR_TOLERANCE);
		CarTrip shopTrip = tripTo(shop, 27000, false);
		assertEquals(DRIVING_COST_EUR + 11.25, commuterOff.calculateCost_MU(shopTrip.person(), shopTrip.trip(),
				shopTrip.elements()), EUR_TOLERANCE);
		var commuterOffCells = commuterOffCounter.snapshotAndResetTotals();
		assertEquals(1L, commuterOffCells.get(new ParkingOutcomeCounter.Cell(ParkingOutcome.PAID_METERED, "fx_bs_ib", "work"))
				.count());
		assertEquals(1L, commuterOffCells.get(new ParkingOutcomeCounter.Cell(ParkingOutcome.PAID_EXPECTED, "fx_bs_ib", "shop"))
				.count());
	}

	/**
	 * With the garage options on, an activity without a coordinate that can reach the mixture fails and names the person, the
	 * activity and the way out; a stay decided by an early rule needs no coordinate and prices as before.
	 */
	@Test
	public void anActivityWithoutACoordinateFailsOnlyWhereTheGarageOptionsNeedIt() {
		Activity shop = PopulationUtils.createActivityFromLinkId("shop", Id.createLinkId("link"));
		shop.setEndTime(39600.0);
		inZone(shop, "fx_bs_ib");
		CarTrip carTrip = tripTo(shop, 34200, false);
		IllegalStateException error = assertThrows(IllegalStateException.class, () -> cost_EUR(carTrip));
		assertTrue(error.getMessage(), error.getMessage().contains("person driver"));
		assertTrue(error.getMessage(), error.getMessage().contains("activity shop has no coordinate"));
		assertTrue(error.getMessage(), error.getMessage().contains("garage_decay_m"));
		assertTrue(error.getMessage(), error.getMessage().contains("garage_decay_commute_m"));
		// A failing call records nothing.
		assertTrue(counter.snapshotAndReset().isEmpty());

		Activity home = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId("link"));
		home.setEndTime(39600.0);
		inZone(home, "fx_bs_ib");
		assertEquals(DRIVING_COST_EUR, cost_EUR(tripTo(home, 34200, false)), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.HOME), countsByOutcome());
	}

	// ------------------------------------------------------------------------------ v3 mechanics (tariff schema 5)

	/** The model of the fixture tariff model (garage options on) with the given cost factor text, or none when null. */
	private static ZoneParkingCarCostModel modelWithFactor(String costFactor, ParkingOutcomeCounter counter) throws Exception {
		BraunschweigCostParameters costParameters = new BraunschweigCostParameters();
		costParameters.carCost_EUR_km = CAR_COST_EUR_KM;
		ParkingConfigGroup parking = new ParkingConfigGroup();
		parking.setEnabled("true");
		parking.setTariffsPath(TARIFFS_PATH);
		if (costFactor != null) {
			parking.setCostFactor(costFactor);
		}
		return new ZoneParkingCarCostModel(costParameters, eqasimTimeInterpretation(), fixtureModel(0.0), counter, parking);
	}

	/**
	 * Spec v3 A3 (ASSUMPTION CF): the parking euros of the trip are multiplied by the factor, the driving cost is not, and the
	 * report sees the UNSCALED cost. The stay is golden case E01 (shop in fx_bs_ib at a destination without a garage, 1 h,
	 * 180 ct): factor 2.0 pays 3.60 EUR parking, 0.5 pays 0.90 EUR, 0 pays none; the cell sums 180 ct in every case. A factor
	 * of 1.0 and the absent parameter price the same bits, and the absent parameter is 1.0.
	 */
	@Test
	public void theCostFactorScalesTheParkingCostOnlyAndTheReportSeesTheUnscaledCost() throws Exception {
		Activity shop = inZone(activityAt("shop", 39600.0, 570000.0, 5750000.0), "fx_bs_ib");
		CarTrip carTrip = tripTo(shop, 34200, false);

		ParkingOutcomeCounter factorCounter = new ParkingOutcomeCounter();
		for (String[] factorAndParking : new String[][] { { "2.0", "3.6" }, { "0.5", "0.9" }, { "0.0", "0.0" },
				{ "10.0", "18.0" } }) {
			ZoneParkingCarCostModel scaled = modelWithFactor(factorAndParking[0], factorCounter);
			assertEquals("factor " + factorAndParking[0], DRIVING_COST_EUR + Double.parseDouble(factorAndParking[1]),
					scaled.calculateCost_MU(carTrip.person(), carTrip.trip(), carTrip.elements()), EUR_TOLERANCE);
		}
		var cell = factorCounter.snapshotAndResetTotals()
				.get(new ParkingOutcomeCounter.Cell(ParkingOutcome.PAID_METERED, "fx_bs_ib", "shop"));
		assertEquals(4L, cell.count());
		assertEquals(4 * 180.0, cell.expectedCostCentsSum(), 0.0);

		double absent = modelWithFactor(null, new ParkingOutcomeCounter()).calculateCost_MU(carTrip.person(), carTrip.trip(),
				carTrip.elements());
		double explicitOne = modelWithFactor("1.0", new ParkingOutcomeCounter()).calculateCost_MU(carTrip.person(),
				carTrip.trip(), carTrip.elements());
		assertEquals(Double.doubleToLongBits(absent), Double.doubleToLongBits(explicitOne));
		assertEquals(DRIVING_COST_EUR + 1.80, absent, EUR_TOLERANCE);
	}

	/**
	 * Spec v3 A5: the person attribute parkingEvExempt = true frees the street stay within ev_free_minutes of the zone
	 * (fx_ev_street_v3: 240 min) with the outcome EV_EXEMPT, also with garage options on; without the attribute the same stay
	 * pays 1.80 EUR; a value that is not a Boolean fails naming the attribute.
	 */
	@Test
	public void anExemptPersonParksFreeWhereTheZoneGrantsItAndAMistypedFlagFails() throws Exception {
		Activity shop = inZone(activityAt("shop", 39600.0, 570000.0, 5750000.0), "fx_ev_street_v3");
		CarTrip exemptTrip = tripTo(shop, 34200, false);
		exemptTrip.person().getAttributes().putAttribute(ZoneParkingCarCostModel.EV_EXEMPT_ATTRIBUTE, true);
		assertEquals(DRIVING_COST_EUR, cost_EUR(exemptTrip), EUR_TOLERANCE);
		var cell = counter.snapshotAndResetTotals().get(new ParkingOutcomeCounter.Cell(ParkingOutcome.EV_EXEMPT,
				"fx_ev_street_v3", "shop"));
		assertEquals(1L, cell.count());
		assertEquals(1L, cell.evExemptCount());
		assertEquals(0.0, cell.expectedCostCentsSum(), 0.0);

		CarTrip ordinaryTrip = tripTo(shop, 34200, false);
		assertEquals(DRIVING_COST_EUR + 1.80, cost_EUR(ordinaryTrip), EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.PAID_METERED), countsByOutcome());

		// Garage options off: the zone calculator prices the same exemption.
		ParkingOutcomeCounter offCounter = new ParkingOutcomeCounter();
		ZoneParkingCarCostModel off = modelWithDecays(0.0, 0.0, offCounter);
		assertEquals(DRIVING_COST_EUR, off.calculateCost_MU(exemptTrip.person(), exemptTrip.trip(), exemptTrip.elements()),
				EUR_TOLERANCE);
		assertEquals(onlyOnce(ParkingOutcome.EV_EXEMPT), ParkingOutcomeCounter.countsByOutcome(offCounter.snapshotAndReset()));

		CarTrip mistyped = tripTo(shop, 34200, false);
		mistyped.person().getAttributes().putAttribute(ZoneParkingCarCostModel.EV_EXEMPT_ATTRIBUTE, "true");
		IllegalStateException error = assertThrows(IllegalStateException.class, () -> cost_EUR(mistyped));
		assertTrue(error.getMessage(), error.getMessage().contains("person driver: parkingEvExempt must be a java.lang.Boolean"));
		assertTrue(counter.snapshotAndReset().isEmpty());
	}

	/**
	 * Spec v3 A1 and A4 through the cost model: a shop stay of 240 chargeable minutes in fx_ia_nolong_v3 (maximum stay 180 min,
	 * no long-stay product, no garage in range) is priced as the street at its metered price, 7.20 EUR, with the outcome
	 * NO_FEASIBLE_OPTION; its cell counts the infeasible street, the stay without a feasible option and the unscaled 720 ct.
	 * The same cell with the garage options switched off (the zone calculator prices it, no option code runs).
	 */
	@Test
	public void aStayWithNoFeasibleOptionIsPricedAsTheStreetAndCountedInTheReport() throws Exception {
		Activity shop = inZone(activityAt("shop", 50400.0, 570000.0, 5750000.0), "fx_ia_nolong_v3");
		CarTrip carTrip = tripTo(shop, 34200, false);
		assertEquals(DRIVING_COST_EUR + 7.20, cost_EUR(carTrip), EUR_TOLERANCE);
		ParkingOutcomeCounter.Cell cell = new ParkingOutcomeCounter.Cell(ParkingOutcome.NO_FEASIBLE_OPTION, "fx_ia_nolong_v3",
				"shop");
		var on = counter.snapshotAndResetTotals().get(cell);
		assertEquals(new ParkingOutcomeCounter.CellTotals(1, 0.0, 0.0, 0, 1, 1, 720.0, 0), on);
		assertEquals(1L, counter.garageOptionCounters().snapshotAndReset().noFeasibleOption());

		ParkingOutcomeCounter offCounter = new ParkingOutcomeCounter();
		ZoneParkingCarCostModel off = modelWithDecays(0.0, 0.0, offCounter);
		assertEquals(DRIVING_COST_EUR + 7.20, off.calculateCost_MU(carTrip.person(), carTrip.trip(), carTrip.elements()),
				EUR_TOLERANCE);
		assertEquals(on, offCounter.snapshotAndResetTotals().get(cell));
	}

	/**
	 * Spec v3 A2 through the cost model with the money coefficient of the fixture, 0.310998 per EUR (the mode choice
	 * coefficient): golden E02 at beta 0 weighs the garage (200 ct) with exp(-0.75); at beta 0.310998 the street weighs
	 * exp(-0.310998 * 1.80), the garage exp(-0.75 - 0.310998 * 2.00), so its probability is lower (0.3074...) and the price is
	 * 180 + 20 * 0.3074 = 186.15, i.e. 186 ct. The report cell carries the probability as paid garage probability and the
	 * unrounded expectation as the expected cost.
	 */
	@Test
	public void theModelPricesTheOptionWeightsWithTheCoefficientOfTheTariffModel() throws Exception {
		ParkingOutcomeCounter priced = new ParkingOutcomeCounter();
		BraunschweigCostParameters costParameters = new BraunschweigCostParameters();
		costParameters.carCost_EUR_km = CAR_COST_EUR_KM;
		ParkingConfigGroup parking = new ParkingConfigGroup();
		parking.setEnabled("true");
		parking.setTariffsPath(TARIFFS_PATH);
		ZoneParkingCarCostModel withPrice = new ZoneParkingCarCostModel(costParameters, eqasimTimeInterpretation(),
				ParkingTariffs.read(fixturePath()), priced, parking);
		Activity shop = inZone(activityAt("shop", 39600.0, 580000.0, 5750000.0), "fx_bs_ib");
		CarTrip carTrip = tripTo(shop, 34200, false);
		assertEquals(DRIVING_COST_EUR + 1.86,
				withPrice.calculateCost_MU(carTrip.person(), carTrip.trip(), carTrip.elements()), EUR_TOLERANCE);
		double beta = 0.310998;
		double streetWeight = StrictMath.exp(-0.0 / 400.0 - beta * 180 / 100);
		double garageWeight = StrictMath.exp(-300.0 / 400.0 - beta * 200 / 100);
		double probability = garageWeight / (streetWeight + garageWeight);
		var cell = priced.snapshotAndResetTotals()
				.get(new ParkingOutcomeCounter.Cell(ParkingOutcome.PAID_EXPECTED, "fx_bs_ib", "shop"));
		assertEquals(0.3074219720, probability, 1e-9);
		assertEquals(probability, cell.garageProbabilitySumPaid(), 1e-9);
		assertEquals(0.0, cell.garageProbabilitySumFree(), 0.0);
		assertEquals(180.0 * (1.0 - probability) + 200.0 * probability, cell.expectedCostCentsSum(), 1e-6);
	}
}
