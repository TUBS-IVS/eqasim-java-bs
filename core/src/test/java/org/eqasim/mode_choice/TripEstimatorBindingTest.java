package org.eqasim.mode_choice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Collections;

import org.apache.commons.io.FileUtils;
import org.eqasim.TestConfigurator;
import org.eqasim.core.components.config.EqasimConfigGroup;
import org.eqasim.core.components.raptor.EqasimRaptorConfigGroup;
import org.eqasim.core.misc.InjectorBuilder;
import org.eqasim.core.scenario.config.GenerateConfig;
import org.eqasim.core.simulation.EqasimConfigurator;
import org.eqasim.core.simulation.mode_choice.EqasimModeChoiceModule;
import org.eqasim.core.simulation.mode_choice.trip_cache.PersonScopedCachedTripEstimator;
import org.eqasim.core.simulation.termination.EqasimTerminationConfigGroup;
import org.junit.After;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contribs.discrete_mode_choice.model.estimation.CachedTripEstimator;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripEstimator;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.timing.TimeInterpretationModule;

import com.google.inject.Injector;

/**
 * The trip estimator that the eqasim module stack injects into the DMC tour estimators. The DMC contrib wraps it in its
 * CachedTripEstimator, whose cache is keyed by DiscreteModeChoiceTrip objects; that class hashes every trip to the same
 * value (DiscreteModeChoiceTrip.hashCode shifts two ints by 32 bits, a no-op in Java) and compares by identity, and the
 * cache is never cleared while one replanning thread runs. Every lookup then scans all trips the thread has estimated so
 * far: the replanning time grows with the square of the replanned persons per thread (eqasim-bs production run
 * prod100_20261031, iteration 1: replanning 7 h 37 min).
 */
public class TripEstimatorBindingTest {
	@After
	public void tearDown() throws IOException {
		FileUtils.deleteDirectory(new File("simulation_output"));
	}

	@Test
	public void testInjectedTripEstimatorIsNotTheIterationLongDmcCache() throws ConfigurationException {
		TripEstimator estimator = createInjector().getInstance(TripEstimator.class);

		assertFalse("the injected trip estimator is the DMC CachedTripEstimator, whose lookups grow with every trip "
				+ "estimated in the replanning thread", estimator instanceof CachedTripEstimator);
		assertTrue(estimator instanceof PersonScopedCachedTripEstimator);
	}

	static Injector createInjector() throws ConfigurationException {
		// The configuration that is usually generated for eqasim simulations, as in TestSpecialModeChoiceCases
		Config config = ConfigUtils.createConfig();
		CommandLine cmd = new CommandLine.Builder(new String[] {}).build();

		new GenerateConfig(cmd, "", 1.0, 1, 1).run(config);
		config.addModule(new EqasimRaptorConfigGroup());
		config.removeModule(EqasimTerminationConfigGroup.GROUP_NAME);
		EqasimConfigGroup.get(config).setEstimator("walk", EqasimModeChoiceModule.ZERO_ESTIMATOR_NAME);
		EqasimConfigGroup.get(config).setEstimator("bike", EqasimModeChoiceModule.ZERO_ESTIMATOR_NAME);

		EqasimConfigurator configurator = new TestConfigurator();
		Scenario scenario = ScenarioUtils.createScenario(config);

		// One link avoids the assertion in NetworkRoutingInclAccessEgressModule
		Node node = scenario.getNetwork().getFactory().createNode(Id.createNodeId("node"), new Coord(0.0, 0.0));
		Link link = scenario.getNetwork().getFactory().createLink(Id.createLinkId("link"), node, node);
		link.setAllowedModes(Collections.singleton("car"));
		scenario.getNetwork().addNode(node);
		scenario.getNetwork().addLink(link);

		return new InjectorBuilder(scenario, configurator) //
				.addOverridingModules(configurator.getModules(config)) //
				.addOverridingModule(new TimeInterpretationModule()) //
				.build();
	}
}
