package org.eqasim.braunschweig.mode_choice;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.eqasim.braunschweig.BraunschweigConfigurator;
import org.eqasim.braunschweig.mode_choice.constraints.BorderTourVehicleConstraint;
import org.eqasim.braunschweig.mode_choice.constraints.BorderTourVehicleSummaryListener;
import org.eqasim.braunschweig.scenario.RunAdaptConfig;
import org.eqasim.core.scenario.config.GenerateConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourConstraintFactory;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.controler.listener.ControllerListener;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.ActivityFacility;

import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.TypeLiteral;

/**
 * Selects the border tour vehicle constraint by its name in the real controller injector (config chain as in
 * {@code ParkingInjectorTest}, without parking) and pins the provider's check of the tour finder activity types
 * (eqasim-bs#678).
 */
public class BorderTourVehicleConstraintBindingTest {
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void selectedNameResolvesToTheBorderTourFactoryAndItsSummaryListener() throws Exception {
		Injector injector = controller(dmcConfig -> {
			Set<String> constraints = new HashSet<>(dmcConfig.getTourConstraints());
			constraints.add(BraunschweigModeChoiceModule.BORDER_TOUR_VEHICLE_CONSTRAINT_NAME);
			dmcConfig.setTourConstraints(constraints);
		}).getInjector();

		Map<String, Provider<TourConstraintFactory>> factories = injector
				.getInstance(Key.get(new TypeLiteral<Map<String, Provider<TourConstraintFactory>>>() {
				}));
		Provider<TourConstraintFactory> provider = factories
				.get(BraunschweigModeChoiceModule.BORDER_TOUR_VEHICLE_CONSTRAINT_NAME);

		assertNotNull(provider);
		assertTrue(provider.get() instanceof BorderTourVehicleConstraint.Factory);

		Set<ControllerListener> listeners = injector.getInstance(Key.get(new TypeLiteral<Set<ControllerListener>>() {
		}));
		assertTrue(listeners.stream().anyMatch(BorderTourVehicleSummaryListener.class::isInstance));
	}

	@Test
	public void selectedConstraintRejectsOtherTourFinderActivityTypes() {
		DiscreteModeChoiceConfigGroup dmcConfig = new DiscreteModeChoiceConfigGroup();
		dmcConfig.setTourConstraints(List.of(BraunschweigModeChoiceModule.BORDER_TOUR_VEHICLE_CONSTRAINT_NAME));
		dmcConfig.getActivityTourFinderConfigGroup().setActivityTypes(List.of("home"));

		IllegalStateException error = assertThrows(IllegalStateException.class, () -> provide(dmcConfig));

		assertTrue(error.getMessage(), error.getMessage().contains("[home]"));
		assertTrue(error.getMessage(), error.getMessage().contains("BorderTourVehicleContinuity"));
	}

	@Test
	public void providerAcceptsHomeAndOutsideOrAConfigWithoutTheConstraint() {
		DiscreteModeChoiceConfigGroup selected = new DiscreteModeChoiceConfigGroup();
		selected.setTourConstraints(List.of(BraunschweigModeChoiceModule.BORDER_TOUR_VEHICLE_CONSTRAINT_NAME));
		selected.getActivityTourFinderConfigGroup().setActivityTypes(List.of("outside", "home"));
		assertNotNull(provide(selected));

		DiscreteModeChoiceConfigGroup notSelected = new DiscreteModeChoiceConfigGroup();
		notSelected.setTourConstraints(List.of("VehicleContinuity"));
		notSelected.getActivityTourFinderConfigGroup().setActivityTypes(List.of("home"));
		assertNotNull(provide(notSelected));
	}

	private static BorderTourVehicleConstraint.Factory provide(DiscreteModeChoiceConfigGroup dmcConfig) {
		CommandLine cmd;
		try {
			cmd = new CommandLine.Builder(new String[0]).allowAnyOption(true).build();
		} catch (CommandLine.ConfigurationException e) {
			throw new IllegalStateException(e);
		}
		return new BraunschweigModeChoiceModule(cmd).provideBorderTourVehicleConstraintFactory(dmcConfig,
				planTrips -> Id.create("home", ActivityFacility.class));
	}

	/** The controller of a prepared scenario (empty population and network); nothing is injected yet. */
	private Controler controller(Consumer<DiscreteModeChoiceConfigGroup> dmcChange)
			throws Exception {
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
		dmcChange.accept(DiscreteModeChoiceConfigGroup.getOrCreate(config));

		Scenario scenario = ScenarioUtils.createScenario(config);
		configurator.configureScenario(scenario);
		Controler controller = new Controler(scenario);
		configurator.configureController(controller);
		return controller;
	}
}
