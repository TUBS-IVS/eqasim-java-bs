package org.eqasim.braunschweig.analysis;

import static org.junit.Assert.assertEquals;

import java.util.Set;

import org.eqasim.braunschweig.BraunschweigConfigurator;
import org.eqasim.braunschweig.scenario.RunAdaptConfig;
import org.eqasim.core.scenario.config.GenerateConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.controler.listener.ControllerListener;
import org.matsim.core.scenario.ScenarioUtils;

import com.google.inject.Key;
import com.google.inject.TypeLiteral;

/**
 * The Braunschweig configurator installs the loop trip distance listener in the real controller injector, and every
 * dependency of the listener is bound there (config chain as in the mode choice binding tests) (eqasim-bs#679).
 */
public class LoopTripDistanceStatsBindingTest {
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void the_listener_is_installed_once_in_every_run() throws Exception {
		CommandLine cmd = new CommandLine.Builder(new String[0]).allowAnyOption(true).build();
		BraunschweigConfigurator configurator = new BraunschweigConfigurator(cmd);
		Config config = ConfigUtils.createConfig();
		configurator.updateConfig(config);
		new GenerateConfig(cmd, "", 0.01, 0, 1).run(config);
		RunAdaptConfig.adaptConfiguration(config, "");
		config.controller().setOutputDirectory(folder.newFolder("output").toString());
		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setLastIteration(0);
		configurator.updateConfig(config);

		Scenario scenario = ScenarioUtils.createScenario(config);
		configurator.configureScenario(scenario);
		Controler controller = new Controler(scenario);
		configurator.configureController(controller);

		Set<ControllerListener> listeners = controller.getInjector()
				.getInstance(Key.get(new TypeLiteral<Set<ControllerListener>>() {
				}));
		assertEquals(1, listeners.stream().filter(LoopTripDistanceStatsListener.class::isInstance).count());
	}
}
