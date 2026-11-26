package org.eqasim.core.simulation.mode_choice.filters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.eqasim.TestConfigurator;
import org.eqasim.core.components.config.EqasimConfigGroup;
import org.eqasim.core.components.raptor.EqasimRaptorConfigGroup;
import org.eqasim.core.misc.InjectorBuilder;
import org.eqasim.core.scenario.config.GenerateConfig;
import org.eqasim.core.scenario.routing.PlanRouter;
import org.eqasim.core.simulation.EqasimConfigurator;
import org.eqasim.core.simulation.mode_choice.EqasimModeChoiceModule;
import org.eqasim.core.simulation.mode_choice.parameters.ModeParameters;
import org.eqasim.core.simulation.termination.EqasimTerminationConfigGroup;
import org.junit.After;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceModel;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.mode_availability.ModeAvailability;
import org.matsim.contribs.discrete_mode_choice.modules.AbstractDiscreteModeChoiceExtension;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.replanning.PlanStrategy;
import org.matsim.core.router.RoutingModule;
import org.matsim.core.router.RoutingRequest;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.timing.TimeInterpretationModule;

import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.name.Names;

/**
 * The MATSim loop's mode choice replanning (eqasim-bs#678, owner decision 2026-11-26): the injector's DiscreteModeChoice
 * plan strategy (random selector, plan copy, the contrib's DiscreteModeChoiceReplanningModule, its routing check) gets
 * the {@link KeepFilteredTripsModel}-wrapped model. A trip of a tour the filter rejects (a border tour via eqasim's
 * OutsideFilter) is routed again, but with eqasim's routing: a pt trip that had fallen back to walk keeps its
 * walk-fallback stamp, a routed pt trip stays pt. A trip of an accepted tour is re-chosen as before. Before the fix the
 * contrib re-routed the rejected tours with the plain router, which dropped the stamp.
 */
public class FilteredTripsReplanningTest {
	private static final double ROUTED_TRAVEL_TIME = 3600.0;

	@After
	public void tearDown() throws IOException {
		FileUtils.deleteDirectory(new File("simulation_output"));
	}

	private static Injector createInjector(Scenario scenario, Config config) throws ConfigurationException {
		EqasimConfigurator configurator = new TestConfigurator();
		return new InjectorBuilder(scenario, configurator) //
				.addOverridingModules(configurator.getModules(config)) //
				.addOverridingModule(new StaticModesModule()) //
				.addOverridingModule(new TimeInterpretationModule()) //
				.build();
	}

	/** The generated eqasim configuration (as in TestSpecialModeChoiceCases), with zero-utility static modes. */
	private static Config config() {
		Config config = ConfigUtils.createConfig();
		try {
			new GenerateConfig(new CommandLine.Builder(new String[] {}).build(), "", 1.0, 1, 1).run(config);
		} catch (ConfigurationException e) {
			throw new IllegalStateException(e);
		}
		config.addModule(new EqasimRaptorConfigGroup());
		config.removeModule(EqasimTerminationConfigGroup.GROUP_NAME);
		DiscreteModeChoiceConfigGroup.getOrCreate(config).setModeAvailability("static");
		for (String mode : List.of(TransportMode.walk, TransportMode.bike, TransportMode.pt)) {
			EqasimConfigGroup.get(config).setEstimator(mode, EqasimModeChoiceModule.ZERO_ESTIMATOR_NAME);
		}
		return config;
	}

	private static Scenario scenario(Config config) {
		Scenario scenario = ScenarioUtils.createScenario(config);
		// One link avoids the assertion in NetworkRoutingInclAccessEgressModule
		Node node = scenario.getNetwork().getFactory().createNode(Id.createNodeId("node"), new Coord(0.0, 0.0));
		Link link = scenario.getNetwork().getFactory().createLink(Id.createLinkId("link"), node, node);
		link.setAllowedModes(Collections.singleton("car"));
		scenario.getNetwork().addNode(node);
		scenario.getNetwork().addLink(link);
		return scenario;
	}

	private static Activity activity(PopulationFactory factory, String type, double x, Double endTime) {
		Activity activity = factory.createActivityFromCoord(type, new Coord(x, 0.0));
		activity.setLinkId(Id.createLinkId("link"));
		if (endTime != null) {
			activity.setEndTime(endTime);
		}
		return activity;
	}

	private static Leg leg(PopulationFactory factory, String mode, String routingMode) {
		Leg leg = factory.createLeg(mode);
		TripStructureUtils.setRoutingMode(leg, routingMode);
		leg.setRoute(RouteUtils.createGenericRouteImpl(Id.createLinkId("link"), Id.createLinkId("link")));
		leg.setTravelTime(600.0);
		return leg;
	}

	/** outside > work by a pt trip that fell back to walk (stamped), work > outside by a routed pt trip. */
	private static Plan borderPlan(PopulationFactory factory) {
		Person person = factory.createPerson(Id.createPersonId("border"));
		Plan plan = factory.createPlan();
		plan.addActivity(activity(factory, "outside", 0.0, 7.0 * 3600.0));
		Leg fallback = leg(factory, TransportMode.walk, TransportMode.walk);
		fallback.getAttributes().putAttribute(PlanRouter.WALK_FALLBACK_ATTRIBUTE, TransportMode.pt);
		plan.addLeg(fallback);
		plan.addActivity(activity(factory, "work", 800.0, 16.0 * 3600.0));
		plan.addLeg(leg(factory, TransportMode.walk, TransportMode.pt));
		plan.addActivity(PopulationUtils.createStageActivityFromCoordLinkIdAndModePrefix(new Coord(900.0, 0.0),
				Id.createLinkId("link"), TransportMode.pt));
		plan.addLeg(leg(factory, TransportMode.pt, TransportMode.pt));
		plan.addActivity(PopulationUtils.createStageActivityFromCoordLinkIdAndModePrefix(new Coord(9000.0, 0.0),
				Id.createLinkId("link"), TransportMode.pt));
		plan.addLeg(leg(factory, TransportMode.walk, TransportMode.pt));
		plan.addActivity(activity(factory, "outside", 10000.0, null));
		person.addPlan(plan);
		return plan;
	}

	/** home > work > home by walk: a tour the filters accept. */
	private static Plan residentPlan(PopulationFactory factory) {
		Person person = factory.createPerson(Id.createPersonId("resident"));
		Plan plan = factory.createPlan();
		plan.addActivity(activity(factory, "home", 0.0, 8.0 * 3600.0));
		plan.addLeg(leg(factory, TransportMode.walk, TransportMode.walk));
		plan.addActivity(activity(factory, "work", 800.0, 17.0 * 3600.0));
		plan.addLeg(leg(factory, TransportMode.walk, TransportMode.walk));
		plan.addActivity(activity(factory, "home", 0.0, null));
		person.addPlan(plan);
		return plan;
	}

	@Test
	public void theLoopReRoutesRejectedToursWithEqasimRoutingAndReChoosesTheOthers() throws ConfigurationException {
		Config config = config();
		Scenario scenario = scenario(config);
		Injector injector = createInjector(scenario, config);
		PopulationFactory factory = scenario.getPopulation().getFactory();

		// the controller injector hands the wrapped model to everything that injects the model, the strategy included
		assertTrue(injector.getInstance(DiscreteModeChoiceModel.class) instanceof KeepFilteredTripsModel);
		// the strategy as MATSim's controller looks it up (AbstractModule.addPlanStrategyBinding)
		PlanStrategy strategy = injector.getInstance(Key.get(PlanStrategy.class, Names.named("DiscreteModeChoice")));

		Plan borderPlan = borderPlan(factory);
		Plan residentPlan = residentPlan(factory);
		Person border = borderPlan.getPerson();
		Person resident = residentPlan.getPerson();

		strategy.init(() -> 1);
		strategy.run(border);
		strategy.run(resident);
		strategy.finish();

		// the border plan (the strategy works on a copy): same structure, every trip routed again by eqasim's routing
		List<Leg> borderLegs = TripStructureUtils.getLegs(border.getSelectedPlan());
		assertEquals(2, borderLegs.size());
		Leg fallback = borderLegs.get(0);
		assertEquals(TransportMode.walk, fallback.getMode());
		assertEquals(TransportMode.walk, TripStructureUtils.getRoutingMode(fallback));
		assertEquals(TransportMode.pt, fallback.getAttributes().getAttribute(PlanRouter.WALK_FALLBACK_ATTRIBUTE));
		assertEquals(ROUTED_TRAVEL_TIME, fallback.getTravelTime().seconds(), 0.0);
		Leg pt = borderLegs.get(1);
		assertEquals(TransportMode.pt, pt.getMode());
		assertEquals(TransportMode.pt, TripStructureUtils.getRoutingMode(pt));
		assertEquals(ROUTED_TRAVEL_TIME, pt.getTravelTime().seconds(), 0.0);
		assertEquals(null, pt.getAttributes().getAttribute(PlanRouter.WALK_FALLBACK_ATTRIBUTE));

		// the resident's trips are re-chosen (walk or bike, both of zero utility) and routed as before
		List<Leg> residentLegs = TripStructureUtils.getLegs(resident.getSelectedPlan());
		assertEquals(2, residentLegs.size());
		for (Leg leg : residentLegs) {
			assertTrue(leg.getMode(), List.of(TransportMode.walk, TransportMode.bike).contains(leg.getMode()));
			assertEquals(ROUTED_TRAVEL_TIME, leg.getTravelTime().seconds(), 0.0);
		}
	}

	/** walk, bike and pt available with zero utility, routed by a static router; as in TestSpecialModeChoiceCases. */
	static private class StaticModesModule extends AbstractDiscreteModeChoiceExtension {
		@Override
		protected void installExtension() {
			bindModeAvailability("static").toInstance(new StaticModeAvailability());
			bind(ModeParameters.class).toInstance(new ModeParameters());
			for (String mode : List.of(TransportMode.walk, TransportMode.bike, TransportMode.pt)) {
				addRoutingModuleBinding(mode).toInstance(new StaticRoutingModule(mode));
			}
		}
	}

	/** One leg of its own mode, with a travel time ({@link #ROUTED_TRAVEL_TIME}) that marks it as newly routed. */
	static private class StaticRoutingModule implements RoutingModule {
		private final String mode;

		StaticRoutingModule(String mode) {
			this.mode = mode;
		}

		@Override
		public List<? extends PlanElement> calcRoute(RoutingRequest request) {
			Leg leg = PopulationUtils.createLeg(mode);
			leg.setTravelTime(ROUTED_TRAVEL_TIME);
			// a route, as every real router sets one (the contrib's routing check requires it)
			leg.setRoute(RouteUtils.createGenericRouteImpl(Id.createLinkId("link"), Id.createLinkId("link")));
			return Collections.singletonList(leg);
		}
	}

	static private class StaticModeAvailability implements ModeAvailability {
		@Override
		public Collection<String> getAvailableModes(Person person, List<DiscreteModeChoiceTrip> trips) {
			return Arrays.asList(TransportMode.bike, TransportMode.walk);
		}
	}
}
