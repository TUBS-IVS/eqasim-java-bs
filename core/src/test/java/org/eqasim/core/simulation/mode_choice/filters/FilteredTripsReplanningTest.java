package org.eqasim.core.simulation.mode_choice.filters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;

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
import org.matsim.contribs.discrete_mode_choice.replanning.DiscreteModeChoiceAlgorithm;
import org.matsim.contribs.discrete_mode_choice.replanning.TripListConverter;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.RoutingModule;
import org.matsim.core.router.RoutingRequest;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.timing.TimeInterpretationModule;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;

/**
 * The MATSim loop's mode choice replanning (eqasim-bs#678): the contrib's DiscreteModeChoiceReplanningModule runs a
 * DiscreteModeChoiceAlgorithm over the injected DiscreteModeChoiceModel and TripListConverter, as composed here. A trip
 * of a tour the filter rejects (a border tour via eqasim's OutsideFilter) must come out as it went in, the walk-fallback
 * stamp included; a trip of an accepted tour is still re-chosen and re-inserted. Before, the contrib re-routed the
 * rejected tours with the plain router, so the stamp was lost and routed pt could become walk-only.
 */
public class FilteredTripsReplanningTest {
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
	public void theLoopKeepsTripsOfRejectedToursAndReChoosesTheOthers() throws ConfigurationException {
		Config config = config();
		Scenario scenario = scenario(config);
		Injector injector = createInjector(scenario, config);
		PopulationFactory factory = scenario.getPopulation().getFactory();

		// as DiscreteModeChoiceReplanningModule.getPlanAlgoInstance composes it
		DiscreteModeChoiceAlgorithm algorithm = new DiscreteModeChoiceAlgorithm(new Random(1),
				injector.getInstance(DiscreteModeChoiceModel.class), factory,
				injector.getInstance(TripListConverter.class));

		Plan border = borderPlan(factory);
		List<PlanElement> borderBefore = new ArrayList<>(border.getPlanElements());
		Plan resident = residentPlan(factory);
		List<Leg> residentBefore = TripStructureUtils.getLegs(resident);

		algorithm.run(border);
		algorithm.run(resident);

		List<PlanElement> borderAfter = border.getPlanElements();
		assertEquals(borderBefore.size(), borderAfter.size());
		for (int index = 0; index < borderBefore.size(); index++) {
			assertSame("element " + index, borderBefore.get(index), borderAfter.get(index));
		}
		assertEquals(TransportMode.pt,
				((Leg) borderAfter.get(1)).getAttributes().getAttribute(PlanRouter.WALK_FALLBACK_ATTRIBUTE));

		// the resident's trips are re-chosen and re-inserted as before (the static router returns new legs)
		List<Leg> residentAfter = TripStructureUtils.getLegs(resident);
		assertEquals(2, residentAfter.size());
		for (int index = 0; index < residentAfter.size(); index++) {
			assertNotSame(residentBefore.get(index), residentAfter.get(index));
		}
	}

	/** walk, bike and pt available with zero utility, routed by a static router; as in TestSpecialModeChoiceCases. */
	static private class StaticModesModule extends AbstractDiscreteModeChoiceExtension {
		@Override
		protected void installExtension() {
			bindModeAvailability("static").toInstance(new StaticModeAvailability());
			bind(ModeParameters.class).toInstance(new ModeParameters());
			for (String mode : List.of(TransportMode.walk, TransportMode.bike, TransportMode.pt)) {
				addRoutingModuleBinding(mode).to(StaticRoutingModule.class);
			}
		}
	}

	@Singleton
	static private class StaticRoutingModule implements RoutingModule {
		private final PopulationFactory populationFactory;

		@Inject
		public StaticRoutingModule(PopulationFactory populationFactory) {
			this.populationFactory = populationFactory;
		}

		@Override
		public List<? extends PlanElement> calcRoute(RoutingRequest request) {
			Leg leg = populationFactory.createLeg(TransportMode.walk);
			leg.setTravelTime(3600.0);
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
