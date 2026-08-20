package org.eqasim.braunschweig;

import java.util.Collections;
import java.util.Set;

import org.eqasim.core.scenario.validation.VehiclesValidator;
import org.eqasim.core.simulation.vdf.VDFConfigGroup;
import org.eqasim.core.simulation.vdf.engine.VDFEngineConfigGroup;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.simwrapper.SimWrapperModule;

public class RunSimulation {
	static public void main(String[] args) throws ConfigurationException {
		CommandLine cmd = new CommandLine.Builder(args) //
				.requireOptions("config-path") //
				.allowOptions("simwrapper") //
				.allowPrefixes("mode-choice-parameter", "cost-parameter", "use-vdf", "use-vdf-engine") //
				.build();

		BraunschweigConfigurator configurator = new BraunschweigConfigurator(cmd);
		Config config = ConfigUtils.loadConfig(cmd.getOptionStrict("config-path"));
		configurator.updateConfig(config);

		if (cmd.getOption("use-vdf").map(Boolean::parseBoolean).orElse(false)) {
			config.qsim().setFlowCapFactor(1e9);
			config.qsim().setStorageCapFactor(1e9);

			VDFConfigGroup vdfConfig = new VDFConfigGroup();
			config.addModule(vdfConfig);

			vdfConfig.setCapacityFactor(0.5);
			vdfConfig.setModes(Set.of("car", "car_passenger"));

			if (cmd.getOption("use-vdf-engine").map(Boolean::parseBoolean).orElse(false)) {
				VDFEngineConfigGroup engineConfig = new VDFEngineConfigGroup();
				engineConfig.setModes(Set.of("car", "car_passenger"));
				// VDF engine API change (eqasim-java 2.2.0 / upstream #544): the boolean
				// setGenerateNetworkEvents(false) was replaced by an interval. The module
				// computes generateNetworkEvents = interval > 0 && (it % interval == 0), so
				// interval 0 preserves the previous "never emit network events" behaviour.
				engineConfig.setGenerateNetworkEventsInterval(0);
				config.addModule(engineConfig);

				config.qsim().setMainModes(Collections.emptySet());
			}
		}

		cmd.applyConfiguration(config);
		VehiclesValidator.validate(config);

		Scenario scenario = ScenarioUtils.createScenario(config);
		configurator.configureScenario(scenario);
		ScenarioUtils.loadScenario(scenario);
		configurator.adjustScenario(scenario);

		Controler controller = new Controler(scenario);
		configurator.configureController(controller);

		// Optional SimWrapper dashboards (network volumes, mode share, trips/legs).
		// Off by default so a standard run is byte-identical; enabled from the
		// pipeline via --simwrapper true (config key: simwrapper_dashboards).
		if (cmd.getOption("simwrapper").map(Boolean::parseBoolean).orElse(false)) {
			controller.addOverridingModule(new SimWrapperModule());
		}

		controller.run();

		// Terminate explicitly. MATSim's SimWrapper dashboard generation reads CSV
		// output through tablesaw, whose univocity-parsers backend starts a
		// NON-DAEMON "input reading thread"; that thread is left parked in
		// FixedInstancePool.allocate when the reader is not closed, so DestroyJavaVM
		// waits for it forever and the JVM never exits even though the controler has
		// finished and every output (incl. all SimWrapper dashboards) is written.
		// Diagnosed by jstack on the 100 % run of 2026-08-20, where the process hung
		// for hours after "closing the logfile", which stalls the synpp pipeline
		// (matsim.simulation.run never returns) and blocks every downstream stage.
		// The leak is upstream and out of our reach; this batch entry point therefore
		// guarantees termination itself. Safe at this point: controller.run() has
		// returned, so all shutdown listeners (dashboards included) have completed.
		System.exit(0);
	}
}