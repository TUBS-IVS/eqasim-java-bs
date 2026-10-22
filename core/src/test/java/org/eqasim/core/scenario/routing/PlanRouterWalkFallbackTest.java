package org.eqasim.core.scenario.routing;

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
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripRouterFactoryBuilderWithDefaults;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.util.List;

/**
 * Verifies that the plan router counts and marks pt trips that it replaces by
 * a plain walk because the pt route consists of walk legs only.
 */
public class PlanRouterWalkFallbackTest {
	private PlanRouter router;
	private Scenario scenario;

	private void buildScenario() {
		Config config = ConfigUtils.createConfig();
		config.transit().setUseTransit(true);
		scenario = ScenarioUtils.createScenario(config);

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
		Departure d = f.createDeparture(Id.create("d1", Departure.class), 8.0 * 3600.0);
		route.addDeparture(d);
		TransitLine line = f.createTransitLine(Id.create("line", TransitLine.class));
		line.addRoute(route);
		schedule.addTransitLine(line);

		TripRouter tripRouter = new TripRouterFactoryBuilderWithDefaults().build(scenario).get();
		router = new PlanRouter(scenario.getActivityFacilities(), tripRouter);
	}

	private Plan createPlan(String mode, double departure) {
		PopulationFactory pf = scenario.getPopulation().getFactory();
		Person person = pf.createPerson(Id.createPersonId("p" + mode + departure));
		Plan plan = pf.createPlan();
		Activity home = pf.createActivityFromCoord("home", new Coord(100.0, 0.0));
		home.setEndTime(departure);
		plan.addActivity(home);
		plan.addLeg(pf.createLeg(mode));
		plan.addActivity(pf.createActivityFromCoord("work", new Coord(4900.0, 0.0)));
		person.addPlan(plan);
		return plan;
	}

	@Test
	public void ptTripWithoutServiceIsCountedAndMarked() {
		buildScenario();

		// Served by the only departure at 08:00: routed by pt, no fallback
		Plan served = createPlan("pt", 7.0 * 3600.0 + 3000.0);
		router.run(served, true, Set.of());
		for (Leg leg : TripStructureUtils.getLegs(served)) {
			Assert.assertNull(leg.getAttributes().getAttribute(PlanRouter.WALK_FALLBACK_ATTRIBUTE));
		}
		Assert.assertEquals(1, (int) router.getRoutedTripCounts().get("pt"));
		Assert.assertNull(router.getWalkFallbackCounts().get("pt"));

		// After the last departure: only walk legs, replaced by a plain walk
		Plan unserved = createPlan("pt", 20.0 * 3600.0);
		router.run(unserved, true, Set.of());
		List<Leg> legs = TripStructureUtils.getLegs(unserved);
		Assert.assertFalse(legs.isEmpty());
		for (Leg leg : legs) {
			Assert.assertEquals(TransportMode.walk, leg.getMode());
			Assert.assertEquals("pt", leg.getAttributes().getAttribute(PlanRouter.WALK_FALLBACK_ATTRIBUTE));
		}
		Assert.assertEquals(1, (int) router.getWalkFallbackCounts().get("pt"));
		Assert.assertEquals(2, (int) router.getRoutedTripCounts().get("pt"));

		// Counts accumulate over plans
		router.run(createPlan("pt", 21.0 * 3600.0), true, Set.of());
		Assert.assertEquals(2, (int) router.getWalkFallbackCounts().get("pt"));
		Assert.assertEquals(3, (int) router.getRoutedTripCounts().get("pt"));
	}

	@Test
	public void walkTripCarriesNoAttribute() {
		buildScenario();
		Plan plan = createPlan("walk", 9.0 * 3600.0);
		router.run(plan, true, Set.of());
		for (Leg leg : TripStructureUtils.getLegs(plan)) {
			Assert.assertNull(leg.getAttributes().getAttribute(PlanRouter.WALK_FALLBACK_ATTRIBUTE));
		}
		Assert.assertNull(router.getWalkFallbackCounts().get("walk"));
	}
}
