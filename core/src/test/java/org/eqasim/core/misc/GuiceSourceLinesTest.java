package org.eqasim.core.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.eqasim.TestConfigurator;
import org.eqasim.core.components.config.EqasimConfigGroup;
import org.eqasim.core.components.raptor.EqasimRaptorConfigGroup;
import org.eqasim.core.scenario.config.GenerateConfig;
import org.eqasim.core.simulation.EqasimConfigurator;
import org.eqasim.core.simulation.mode_choice.EqasimModeChoiceModule;
import org.eqasim.core.simulation.termination.EqasimTerminationConfigGroup;
import org.junit.After;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.timing.TimeInterpretationModule;

import com.google.inject.AbstractModule;
import com.google.inject.Binding;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.google.inject.name.Names;

/**
 * Guice resolves the source file and line of every {@code @Provides} method from its class file. They appear in the text of
 * a binding, in Guice error messages and in the module graph that MATSim writes. This test fails when the lookup cannot read
 * the class files of this build, as it did while the ASM inside the default Guice jar stopped at Java 21 (why the parent pom
 * replaces that jar, and the maintenance rule: the comment at the Guice dependency there).
 *
 * A failed lookup shows as "(Unknown Source)" in the text of the binding instead of "(File.java:line)". Guice logs only the
 * first failure of a JVM, so the text, not the log, is the signal for a test that shares its JVM with others.
 */
public class GuiceSourceLinesTest {
	/** {@code @Provides <class>.<method>(<location>)}, the text of a provider method binding. */
	private static final Pattern PROVIDER_METHOD = Pattern
			.compile("Provides\\S*\\s+([\\w.$]+)\\.([\\w$<>]+)\\(([^)]*)\\)");

	/** A resolved location: the source file and a line number. */
	private static final Pattern RESOLVED_LOCATION = Pattern.compile("[\\w$]+\\.java:\\d+");

	@After
	public void tearDown() throws IOException {
		FileUtils.deleteDirectory(new File("simulation_output"));
	}

	/** A module of this class: its class file has the class file version of this build. */
	static class SampleModule extends AbstractModule {
		@Provides
		@Named("unscoped")
		String provideUnscoped() {
			return "unscoped";
		}

		@Provides
		@Singleton
		@Named("singleton")
		String provideSingleton() {
			return "singleton";
		}
	}

	@Test
	public void providerMethodsOfClassesCompiledForThisBuildHaveASourceLine() {
		Injector injector = Guice.createInjector(new SampleModule());

		for (String name : new String[] { "unscoped", "singleton" }) {
			Binding<String> binding = injector.getBinding(Key.get(String.class, Names.named(name)));

			// printInjector renders exactly these: the binding, its provider and its source
			for (String text : new String[] { String.valueOf(binding), String.valueOf(binding.getProvider()) }) {
				assertTrue("no source line for the provider method of '" + name + "': " + text,
						text.contains("GuiceSourceLinesTest.java:"));
				assertFalse("unresolved source of the provider method of '" + name + "': " + text,
						text.contains("Unknown Source"));
			}
		}
	}

	/**
	 * The injector of the real eqasim module stack, built the way every eqasim tool and RunSimulation build it: every
	 * provider method of an org.eqasim or org.matsim module has a source line. The map is the provider methods that miss
	 * it, so a failure names the classes whose class files the ASM of Guice cannot read.
	 */
	@Test
	public void providerMethodsOfTheEqasimInjectorHaveASourceLine() throws ConfigurationException {
		Injector injector = createInjector();

		Map<String, String> resolved = new TreeMap<>();
		Map<String, String> unresolved = new TreeMap<>();

		for (Binding<?> binding : injector.getBindings().values()) {
			for (String text : new String[] { String.valueOf(binding), String.valueOf(binding.getProvider()),
					String.valueOf(binding.getSource()) }) {
				Matcher matcher = PROVIDER_METHOD.matcher(text);

				while (matcher.find()) {
					String method = matcher.group(1) + "." + matcher.group(2);

					if (!method.startsWith("org.eqasim.") && !method.startsWith("org.matsim.")) {
						continue;
					}

					if (RESOLVED_LOCATION.matcher(matcher.group(3)).matches()) {
						resolved.put(method, matcher.group(3));
					} else {
						unresolved.put(method, matcher.group(3));
					}
				}
			}
		}

		// Positive control: the test looks at provider methods at all
		assertTrue("no provider method of an org.eqasim or org.matsim module found in the injector",
				resolved.size() + unresolved.size() > 0);

		assertEquals("provider methods of the eqasim injector without a source line (" + unresolved.size() + " of "
				+ (resolved.size() + unresolved.size()) + "): " + unresolved, Collections.emptyMap(), unresolved);
	}

	/** The injector of the module stack that the tests of the trip estimator and the production tools use. */
	private static Injector createInjector() throws ConfigurationException {
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
