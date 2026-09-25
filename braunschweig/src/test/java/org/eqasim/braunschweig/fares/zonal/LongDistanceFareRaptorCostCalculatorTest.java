package org.eqasim.braunschweig.fares.zonal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.DefaultRoutingRequest;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorInVehicleCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorIntermodalAccessEgress;
import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorStopFinder;
import ch.sbb.matsim.routing.pt.raptor.RaptorUtils;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptor;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorData;

/**
 * Long-distance fare surcharge in PT routing (ADR-0133 D6). A fast "ICE" (10 min) and a slow "RE"
 * (25 min) connect the same two stops at the same time; the router picks the ICE without the surcharge
 * and the RE with it, and a child under the fare model's minimum age keeps riding the ICE for free.
 */
public class LongDistanceFareRaptorCostCalculatorTest {
	/** 21.90 EUR at the Braunschweig reference value of time: -0.025501 u/min in-vehicle, -0.310998 u/EUR. */
	private static final double SURCHARGE_SECONDS = 2190 / 100.0 / (0.025501 / 60.0 / 0.310998);

	private Scenario scenario;
	private TransitStopFacility from;
	private TransitStopFacility to;

	@Before
	public void setUp() {
		Config config = ConfigUtils.createConfig();
		config.transit().setUseTransit(true);
		scenario = ScenarioUtils.createScenario(config);
		Network network = scenario.getNetwork();
		NetworkFactory nf = network.getFactory();
		Node a = nf.createNode(Id.createNodeId("a"), new Coord(0, 0));
		Node b = nf.createNode(Id.createNodeId("b"), new Coord(30000, 0));
		network.addNode(a);
		network.addNode(b);
		Link ab = nf.createLink(Id.createLinkId("ab"), a, b);
		Link ba = nf.createLink(Id.createLinkId("ba"), b, a);
		network.addLink(ab);
		network.addLink(ba);
		TransitSchedule schedule = scenario.getTransitSchedule();
		TransitScheduleFactory factory = schedule.getFactory();
		from = factory.createTransitStopFacility(Id.create("A", TransitStopFacility.class), new Coord(0, 0), false);
		from.setLinkId(ab.getId());
		to = factory.createTransitStopFacility(Id.create("B", TransitStopFacility.class), new Coord(30000, 0), false);
		to.setLinkId(ab.getId());
		schedule.addStopFacility(from);
		schedule.addStopFacility(to);
		VehicleType type = VehicleUtils.getFactory().createVehicleType(Id.create("train", VehicleType.class));
		scenario.getTransitVehicles().addVehicleType(type);
		line("ICE", 600.0, type);
		line("RE", 1500.0, type);
	}

	private void line(String id, double travelTimeSeconds, VehicleType type) {
		TransitScheduleFactory factory = scenario.getTransitSchedule().getFactory();
		List<TransitRouteStop> stops = List.of(factory.createTransitRouteStop(from, 0.0, 0.0),
				factory.createTransitRouteStop(to, travelTimeSeconds, travelTimeSeconds));
		TransitRoute route = factory.createTransitRoute(Id.create(id + "_r", TransitRoute.class),
				org.matsim.core.population.routes.RouteUtils.createNetworkRoute(List.of(Id.createLinkId("ab"))), stops, "rail");
		Departure departure = factory.createDeparture(Id.create(id + "_d", Departure.class), 8 * 3600.0);
		Id<Vehicle> vehicleId = Id.createVehicleId(id + "_veh");
		departure.setVehicleId(vehicleId);
		route.addDeparture(departure);
		scenario.getTransitVehicles().addVehicle(VehicleUtils.getFactory().createVehicle(vehicleId, type));
		TransitLine line = factory.createTransitLine(Id.create(id, TransitLine.class));
		line.addRoute(route);
		scenario.getTransitSchedule().addTransitLine(line);
	}

	private PtLineScopes scopes() {
		return PtLineScopes.of(Map.of("ICE", new PtLineScopes.Scope(PtLineScopes.SCOPE_LONG_DISTANCE, "rail"),
				"RE", new PtLineScopes.Scope("regional", "rail")));
	}

	private String routedLine(double surchargeSeconds, Integer age) {
		Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId("p"));
		if (age != null) {
			PersonUtils.setAge(person, age);
		}
		LongDistanceFareRaptorCostCalculator calculator = LongDistanceFareRaptorCostCalculator.create(
				scenario.getTransitSchedule(), scenario.getTransitVehicles(), scopes(), new LongDistanceSurchargeContext(),
				surchargeSeconds, 6);
		SwissRailRaptor raptor = new SwissRailRaptor.Builder(data(), scenario.getConfig()).with(calculator).build();
		return lineOf(raptor, person);
	}

	private SwissRailRaptorData data() {
		return SwissRailRaptorData.create(scenario.getTransitSchedule(), scenario.getTransitVehicles(),
				RaptorUtils.createStaticConfig(scenario.getConfig()), scenario.getNetwork(), null);
	}

	private String lineOf(SwissRailRaptor raptor, Person person) {
		List<? extends PlanElement> legs = raptor.calcRoute(DefaultRoutingRequest.withoutAttributes(from, to, 8 * 3600.0 - 60,
				person));
		List<String> lines = new ArrayList<>();
		for (PlanElement element : legs) {
			if (element instanceof Leg leg && leg.getRoute() instanceof TransitPassengerRoute route) {
				lines.add(route.getLineId().toString());
			}
		}
		assertEquals("exactly one PT leg expected, got " + lines, 1, lines.size());
		return lines.get(0);
	}

	@Test
	public void routerPrefersTheFastLongDistanceRideWithoutTheSurcharge() {
		assertEquals("ICE", routedLine(0.0, 30));
	}

	@Test
	public void surchargeRoutesAPayingTravellerOntoTheRegionalTrain() {
		assertEquals("RE", routedLine(SURCHARGE_SECONDS, 30));
		assertEquals("RE", routedLine(SURCHARGE_SECONDS, 10));
		// A person without an age is routed as a paying traveller (the cost model itself fails on a missing age).
		assertEquals("RE", routedLine(SURCHARGE_SECONDS, null));
	}

	@Test
	public void childrenUnderTheMinimumAgeRideTheLongDistanceTrainForFree() {
		assertEquals("ICE", routedLine(SURCHARGE_SECONDS, 4));
	}

	@Test
	public void surchargeIsAddedOnceToTheDefaultInVehicleCostOfLongDistanceVehiclesOnly() {
		LongDistanceFareRaptorCostCalculator calculator = LongDistanceFareRaptorCostCalculator.create(
				scenario.getTransitSchedule(), scenario.getTransitVehicles(), scopes(), new LongDistanceSurchargeContext(),
				1000.0, 6);
		Vehicles vehicles = scenario.getTransitVehicles();
		Person adult = PopulationUtils.getFactory().createPerson(Id.createPersonId("adult"));
		PersonUtils.setAge(adult, 40);
		double marginal = -0.002;
		double base = new DefaultRaptorInVehicleCostCalculator().getInVehicleCost(600.0, marginal, adult,
				vehicles.getVehicles().get(Id.createVehicleId("RE_veh")), null, null);
		assertEquals(base, calculator.getInVehicleCost(600.0, marginal, adult,
				vehicles.getVehicles().get(Id.createVehicleId("RE_veh")), null, null), 1e-12);
		assertEquals(base + 1000.0 * 0.002, calculator.getInVehicleCost(600.0, marginal, adult,
				vehicles.getVehicles().get(Id.createVehicleId("ICE_veh")), null, null), 1e-12);
		assertEquals(Set.of(Id.createVehicleId("ICE_veh")), calculator.longDistanceVehicles());
	}

	@Test
	public void longDistanceDepartureWithoutAKnownVehicleFailsInsteadOfSilentlyDroppingTheSurcharge() {
		TransitLine ice = scenario.getTransitSchedule().getTransitLines().get(Id.create("ICE", TransitLine.class));
		ice.getRoutes().values().iterator().next().getDepartures().values().iterator().next()
				.setVehicleId(Id.createVehicleId("unknown_vehicle"));
		assertThrows(IllegalStateException.class, () -> LongDistanceFareRaptorCostCalculator.create(
				scenario.getTransitSchedule(), scenario.getTransitVehicles(), scopes(), new LongDistanceSurchargeContext(),
				1000.0, 6));
	}

	@Test
	public void stopFinderValuesTheSurchargeForThisPersonAndTripDistance() {
		// A valuation that exempts one person: the router sends that person onto the ICE and everyone else onto
		// the RE; the valuation sees the 30 km straight-line distance of the request.
		List<Double> distances = new ArrayList<>();
		SurchargeValuation valuation = new SurchargeValuation() {
			@Override
			public double surchargeSeconds(Person person, double tripDistanceKm) {
				distances.add(tripDistanceKm);
				return "exempt".equals(person.getId().toString()) ? 0.0 : SURCHARGE_SECONDS;
			}

			@Override
			public double referenceSurchargeSeconds() {
				return SURCHARGE_SECONDS;
			}
		};
		LongDistanceSurchargeContext context = new LongDistanceSurchargeContext();
		LongDistanceFareRaptorCostCalculator calculator = LongDistanceFareRaptorCostCalculator.create(
				scenario.getTransitSchedule(), scenario.getTransitVehicles(), scopes(), context,
				valuation.referenceSurchargeSeconds(), 6);
		LongDistanceSurchargeStopFinder stopFinder = new LongDistanceSurchargeStopFinder(
				new DefaultRaptorStopFinder(new DefaultRaptorIntermodalAccessEgress(), Map.of()), valuation, context);
		SwissRailRaptor raptor = new SwissRailRaptor.Builder(data(), scenario.getConfig()).with(calculator).with(stopFinder)
				.build();
		assertEquals("ICE", lineOf(raptor, PopulationUtils.getFactory().createPerson(Id.createPersonId("exempt"))));
		assertEquals("RE", lineOf(raptor, PopulationUtils.getFactory().createPerson(Id.createPersonId("paying"))));
		assertEquals(30.0, distances.get(0), 1e-9);
		// Every long-distance evaluation found this request's valuation; none fell back to the reference.
		assertEquals(0, calculator.referenceFallbacks());
	}

	@Test
	public void rideWithoutARecordedValuationFallsBackToTheReferenceAndIsCounted() {
		LongDistanceFareRaptorCostCalculator calculator = LongDistanceFareRaptorCostCalculator.create(
				scenario.getTransitSchedule(), scenario.getTransitVehicles(), scopes(), new LongDistanceSurchargeContext(),
				1000.0, 6);
		Person adult = PopulationUtils.getFactory().createPerson(Id.createPersonId("adult"));
		calculator.getInVehicleCost(600.0, -0.002, adult,
				scenario.getTransitVehicles().getVehicles().get(Id.createVehicleId("ICE_veh")), null, null);
		assertEquals(1, calculator.referenceFallbacks());
	}

	@Test
	public void surchargeSecondsFollowTheModeChoiceValueOfTime() {
		assertEquals(SURCHARGE_SECONDS, LongDistanceFareRaptorCostCalculator.surchargeSeconds(2190, -0.025501, -0.310998),
				1e-6);
		assertThrows(IllegalArgumentException.class,
				() -> LongDistanceFareRaptorCostCalculator.surchargeSeconds(2190, 0.01, -0.310998));
	}
}
