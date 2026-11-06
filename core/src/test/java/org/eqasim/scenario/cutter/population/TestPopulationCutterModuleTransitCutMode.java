package org.eqasim.scenario.cutter.population;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eqasim.core.components.transit.departure.DefaultDepartureFinder;
import org.eqasim.core.components.transit.departure.DepartureFinder;
import org.eqasim.core.misc.Constants.TransitCutMode;
import org.eqasim.core.scenario.cutter.extent.CircularScenarioExtent;
import org.eqasim.core.scenario.cutter.population.AttributeKeepingMergeOutsideActivities;
import org.eqasim.core.scenario.cutter.population.DefaultMergeOutsideActivities;
import org.eqasim.core.scenario.cutter.population.MergeOutsideActivities;
import org.eqasim.core.scenario.cutter.population.PlanCutter;
import org.eqasim.core.scenario.cutter.population.PopulationCutterModule;
import org.eqasim.core.scenario.cutter.population.trips.TransitTripProcessor;
import org.eqasim.core.scenario.cutter.population.trips.crossing.transit.DefaultTransitTripCrossingPointFinder;
import org.eqasim.core.scenario.cutter.population.trips.crossing.transit.KeptStopsTransitTripCrossingPointFinder;
import org.eqasim.core.scenario.cutter.population.trips.crossing.transit.TransitTripCrossingPointFinder;
import org.eqasim.core.scenario.cutter.transit.TransitScheduleCutter.KeptRange;
import org.junit.Assert;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.RoutingConfigGroup.TeleportedModeParams;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import com.google.inject.Injector;
import com.google.inject.ProvisionException;

/**
 * The population cutter module binds the kept-stops implementations in the kept-stops mode and today's ones in the
 * extent mode (eqasim-bs#574): a wrong binding would let the cutter run without ever taking the primary path.
 */
public class TestPopulationCutterModuleTransitCutMode {
	private static final Id<TransitLine> LINE = Id.create("L", TransitLine.class);
	private static final Id<TransitRoute> ROUTE = Id.create("R", TransitRoute.class);

	private static Scenario createScenario() {
		Config config = ConfigUtils.createConfig();
		// The cutter supports teleported modes with a speed only (the default config has freespeed-factor ones)
		config.routing().clearTeleportedModeParams();
		TeleportedModeParams walk = new TeleportedModeParams(TransportMode.walk);
		walk.setTeleportedModeSpeed(1.0);
		config.routing().addTeleportedModeParams(walk);
		Scenario scenario = ScenarioUtils.createScenario(config);
		TransitSchedule schedule = scenario.getTransitSchedule();
		TransitScheduleFactory factory = schedule.getFactory();

		TransitStopFacility a = factory.createTransitStopFacility(Id.create("a", TransitStopFacility.class),
				new Coord(0.0, 0.0), false);
		TransitStopFacility b = factory.createTransitStopFacility(Id.create("b", TransitStopFacility.class),
				new Coord(500.0, 0.0), false);
		schedule.addStopFacility(a);
		schedule.addStopFacility(b);

		List<TransitRouteStop> stops = List.of(factory.createTransitRouteStop(a, 0.0, 0.0),
				factory.createTransitRouteStop(b, 60.0, 60.0));
		TransitLine line = factory.createTransitLine(LINE);
		line.addRoute(factory.createTransitRoute(ROUTE, null, stops, "bus"));
		schedule.addTransitLine(line);
		return scenario;
	}

	private static Injector createInjector(TransitCutMode mode,
			Map<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> keptRanges) {
		Scenario scenario = createScenario();

		AbstractModule scenarioModule = new AbstractModule() {
			@Override
			public void install() {
				bind(Network.class).toInstance(scenario.getNetwork());
				bind(Population.class).toInstance(scenario.getPopulation());
				bind(TransitSchedule.class).toInstance(scenario.getTransitSchedule());
				bind(DepartureFinder.class).to(DefaultDepartureFinder.class);
				addTravelTimeBinding(TransportMode.car).toInstance(new FreeSpeedTravelTime());
			}
		};

		PopulationCutterModule cutterModule = new PopulationCutterModule(
				new CircularScenarioExtent(new Coord(0.0, 0.0), 1000.0), 1, 10, Optional.empty(), mode, keptRanges);

		return org.matsim.core.controler.Injector.createInjector(scenario.getConfig(), scenarioModule, cutterModule);
	}

	@Test
	public void theKeptStopsModeBindsTheKeptStopsImplementations() {
		Injector injector = createInjector(TransitCutMode.KEPT_STOPS, Map.of(LINE, Map.of(ROUTE, new KeptRange(0, 1))));

		Assert.assertEquals(KeptStopsTransitTripCrossingPointFinder.class,
				injector.getInstance(TransitTripCrossingPointFinder.class).getClass());
		Assert.assertEquals(AttributeKeepingMergeOutsideActivities.class,
				injector.getInstance(MergeOutsideActivities.class).getClass());

		TransitTripProcessor processor = injector.getInstance(TransitTripProcessor.class);
		Assert.assertTrue(processor.isCollapseExcursions());
		Assert.assertSame(processor, injector.getInstance(TransitTripProcessor.class));
		Assert.assertTrue(injector.getInstance(PlanCutter.class).isCopyActivityAttributes());
	}

	@Test
	public void theExtentModeBindsTodaysImplementations() {
		Injector injector = createInjector(TransitCutMode.EXTENT, Map.of());

		Assert.assertEquals(DefaultTransitTripCrossingPointFinder.class,
				injector.getInstance(TransitTripCrossingPointFinder.class).getClass());
		Assert.assertEquals(DefaultMergeOutsideActivities.class,
				injector.getInstance(MergeOutsideActivities.class).getClass());
		Assert.assertFalse(injector.getInstance(TransitTripProcessor.class).isCollapseExcursions());
		Assert.assertFalse(injector.getInstance(PlanCutter.class).isCopyActivityAttributes());
	}

	@Test
	public void theKeptStopsFinderRefusesRangesOfAnotherSchedule() {
		// R has two stops: a range up to index 3 was computed on another (uncut) schedule
		Injector injector = createInjector(TransitCutMode.KEPT_STOPS, Map.of(LINE, Map.of(ROUTE, new KeptRange(0, 3))));

		ProvisionException e = Assert.assertThrows(ProvisionException.class,
				() -> injector.getInstance(TransitTripCrossingPointFinder.class));
		Assert.assertTrue(e.getCause() instanceof IllegalStateException);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rangesAreRefusedInTheExtentMode() {
		new PopulationCutterModule(new CircularScenarioExtent(new Coord(0.0, 0.0), 1000.0), 1, 10, Optional.empty(),
				TransitCutMode.EXTENT, Map.of(LINE, Map.of(ROUTE, new KeptRange(0, 1))));
	}
}
