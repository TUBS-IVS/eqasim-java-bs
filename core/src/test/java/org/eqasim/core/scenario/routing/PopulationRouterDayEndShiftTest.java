package org.eqasim.core.scenario.routing;

import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.PlansConfigGroup.ActivityDurationInterpretation;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripRouterFactoryBuilderWithDefaults;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.pt.transitSchedule.api.Departure;

/**
 * Runs the routed day-end shift through {@link PopulationRouter} (eqasim-bs ADR-0160): a pt trip after the last
 * departure falls back to a long walk, the walk ends after the latest arrival, the day moves earlier, and the second
 * routing tries pt again at the new departure time. Every trip of the written population is counted once.
 */
public class PopulationRouterDayEndShiftTest {
	private Scenario createScenario() {
		Config config = ConfigUtils.createConfig();
		config.transit().setUseTransit(true);
		Scenario scenario = ScenarioUtils.createScenario(config);

		Network network = scenario.getNetwork();
		Node n1 = network.getFactory().createNode(Id.createNodeId("n1"), new Coord(0.0, 0.0));
		Node n2 = network.getFactory().createNode(Id.createNodeId("n2"), new Coord(5000.0, 0.0));
		network.addNode(n1);
		network.addNode(n2);
		Link link = network.getFactory().createLink(Id.createLinkId("l1"), n1, n2);
		link.setLength(5000.0);
		link.setFreespeed(10.0);
		link.setCapacity(1000.0);
		link.setNumberOfLanes(1.0);
		link.setAllowedModes(Set.of("pt"));
		network.addLink(link);

		TransitSchedule schedule = scenario.getTransitSchedule();
		TransitScheduleFactory f = schedule.getFactory();
		TransitStopFacility a = f.createTransitStopFacility(Id.create("a", TransitStopFacility.class),
				new Coord(100.0, 0.0), false);
		a.setLinkId(link.getId());
		TransitStopFacility b = f.createTransitStopFacility(Id.create("b", TransitStopFacility.class),
				new Coord(4900.0, 0.0), false);
		b.setLinkId(link.getId());
		schedule.addStopFacility(a);
		schedule.addStopFacility(b);

		TransitRouteStop sa = f.createTransitRouteStop(a, 0.0, 0.0);
		TransitRouteStop sb = f.createTransitRouteStop(b, 300.0, 300.0);
		TransitRoute route = f.createTransitRoute(Id.create("r", TransitRoute.class),
				RouteUtils.createLinkNetworkRouteImpl(link.getId(), List.of(), link.getId()), List.of(sa, sb), "bus");
		// the only departure leaves at 08:00
		route.addDeparture(f.createDeparture(Id.create("d1", Departure.class), 8.0 * 3600.0));
		TransitLine line = f.createTransitLine(Id.create("line", TransitLine.class));
		line.addRoute(route);
		schedule.addTransitLine(line);
		return scenario;
	}

	@Test
	public void shiftedPlanIsRoutedAgainWithItsRequestedModeAndCountedOnce() throws InterruptedException {
		Scenario scenario = createScenario();
		PopulationFactory factory = scenario.getPopulation().getFactory();
		Person person = factory.createPerson(Id.createPersonId("late"));
		Plan plan = factory.createPlan();
		Activity home = factory.createActivityFromCoord("home", new Coord(100.0, 0.0));
		// after the only departure: pt finds no connection and becomes a walk of about 2 h
		home.setEndTime(9.0 * 3600.0);
		plan.addActivity(home);
		plan.addLeg(factory.createLeg("pt"));
		plan.addActivity(factory.createActivityFromCoord("work", new Coord(4900.0, 0.0)));
		person.addPlan(plan);
		scenario.getPopulation().addPerson(person);

		TripRouter tripRouter = new TripRouterFactoryBuilderWithDefaults().build(scenario).get();
		DayEndShift shift = new DayEndShift(9.5 * 3600.0);
		PopulationRouter router = new PopulationRouter(1, 10, true, Set.of(),
				() -> new PlanRouter(scenario.getActivityFacilities(), tripRouter), shift);

		router.run(scenario.getPopulation());

		Assert.assertEquals(1, shift.getShiftedPlans());
		Assert.assertEquals(0, shift.getUnresolvedPlans());
		// the walk fallback of the first pass was given its mode back and routed by pt after the shift
		Assert.assertEquals(1, shift.getRestoredTrips());
		Assert.assertEquals(1, shift.getRecoveredTrips());
		Assert.assertTrue(home.getEndTime().seconds() <= 8.0 * 3600.0);

		// routed again as pt at the new departure time, not kept as the walk of the first pass
		List<Leg> legs = TripStructureUtils.getLegs(plan);
		Assert.assertTrue(legs.stream().anyMatch(leg -> leg.getMode().equals(TransportMode.pt)));
		for (Leg leg : legs) {
			Assert.assertNull(leg.getAttributes().getAttribute(PlanRouter.WALK_FALLBACK_ATTRIBUTE));
		}

		// the counts describe the written population: one routed pt trip, no fallback, nothing kept
		Assert.assertEquals(Integer.valueOf(1), router.getRoutedTripCounts().get("pt"));
		Assert.assertNull(router.getWalkFallbackCounts().get("pt"));
		Assert.assertTrue(router.getKeptWalkFallbackCounts().isEmpty());
	}

	@Test
	public void walkFallbackOfAModeTheNextPassDoesNotRouteKeepsItsWalk() {
		Scenario scenario = createScenario();
		PopulationFactory factory = scenario.getPopulation().getFactory();
		Plan plan = factory.createPlan();
		Activity home = factory.createActivityFromCoord("home", new Coord(100.0, 0.0));
		home.setEndTime(9.0 * 3600.0);
		plan.addActivity(home);
		plan.addLeg(factory.createLeg("pt"));
		plan.addActivity(factory.createActivityFromCoord("work", new Coord(4900.0, 0.0)));
		factory.createPerson(Id.createPersonId("p")).addPlan(plan);

		TripRouter tripRouter = new TripRouterFactoryBuilderWithDefaults().build(scenario).get();
		new PlanRouter(scenario.getActivityFacilities(), tripRouter).run(plan, true, Set.of());
		Assert.assertEquals(1, PlanRouter.countWalkFallbackTrips(plan, Set.of()));

		// a pass that routes car only would leave a bare pt leg: the trip keeps its routed walk
		Assert.assertEquals(0, PlanRouter.restoreWalkFallbackModes(plan, Set.of(TransportMode.car)));
		for (Leg leg : TripStructureUtils.getLegs(plan)) {
			Assert.assertEquals(TransportMode.walk, leg.getMode());
			Assert.assertNotNull(leg.getRoute());
		}
		Assert.assertEquals(1, PlanRouter.restoreWalkFallbackModes(plan, Set.of(TransportMode.pt)));
	}

	@Test(expected = IllegalStateException.class)
	public void anotherActivityDurationInterpretationIsRefused() {
		DayEndShift.checkActivityDurationInterpretation(ActivityDurationInterpretation.minOfDurationAndEndTime);
	}
}
