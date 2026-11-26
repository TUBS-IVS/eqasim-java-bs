package org.eqasim.braunschweig.mode_choice;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eqasim.braunschweig.BraunschweigConfigurator;
import org.eqasim.braunschweig.mode_choice.constraints.SameFacilityLeisureTripSummaryListener;
import org.eqasim.braunschweig.mode_choice.constraints.StrollTripConstraint;
import org.eqasim.braunschweig.scenario.RunAdaptConfig;
import org.eqasim.core.scenario.config.GenerateConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripConstraintFactory;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.controler.listener.ControllerListener;
import org.matsim.core.scenario.ScenarioUtils;

import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.TypeLiteral;

/**
 * Selects the stroll trip constraint by its name in the real controller injector (config chain as in
 * {@link SameFacilityLeisureTripConstraintBindingTest}) (eqasim-bs#679).
 */
public class StrollTripConstraintBindingTest {
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void factory_resolves_by_name() throws Exception {
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

		DiscreteModeChoiceConfigGroup dmcConfig = DiscreteModeChoiceConfigGroup.getOrCreate(config);
		Set<String> constraints = new HashSet<>(dmcConfig.getTripConstraints());
		constraints.add(BraunschweigModeChoiceModule.STROLL_TRIP_CONSTRAINT_NAME);
		dmcConfig.setTripConstraints(constraints);

		Scenario scenario = ScenarioUtils.createScenario(config);
		configurator.configureScenario(scenario);
		Controler controller = new Controler(scenario);
		configurator.configureController(controller);
		Injector injector = controller.getInjector();

		Provider<TripConstraintFactory> provider = injector
				.getInstance(Key.get(new TypeLiteral<Map<String, Provider<TripConstraintFactory>>>() {
				})).get(BraunschweigModeChoiceModule.STROLL_TRIP_CONSTRAINT_NAME);

		assertNotNull(provider);
		assertTrue(provider.get() instanceof StrollTripConstraint.Factory);
		// One shared instance: the counters the mode choice fills are the ones the summary listener reports.
		assertSame(provider.get(), injector.getInstance(StrollTripConstraint.Factory.class));
		assertTrue(injector.getInstance(Key.get(new TypeLiteral<Set<ControllerListener>>() {
		})).stream().anyMatch(SameFacilityLeisureTripSummaryListener.class::isInstance));
	}
}
