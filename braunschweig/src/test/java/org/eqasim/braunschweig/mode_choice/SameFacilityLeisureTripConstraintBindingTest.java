package org.eqasim.braunschweig.mode_choice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.eqasim.braunschweig.BraunschweigConfigurator;
import org.eqasim.braunschweig.mode_choice.constraints.SameFacilityLeisureTripConstraint;
import org.eqasim.braunschweig.mode_choice.constraints.LeisureTripRuleSummaryListener;
import org.eqasim.braunschweig.scenario.RunAdaptConfig;
import org.eqasim.core.scenario.config.GenerateConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourConstraint;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourConstraintFactory;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripConstraintFactory;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.controler.listener.ControllerListener;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.ActivityFacility;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.TypeLiteral;

/**
 * Selects the same-facility leisure trip constraint by its name in the real controller injector (config chain as in
 * {@link BorderTourVehicleConstraintBindingTest}) and pins that a config which does not select it leaves it unused
 * (eqasim-bs#679).
 */
public class SameFacilityLeisureTripConstraintBindingTest {
	private static final List<String> MODES = List.of("car", "car_passenger", "pt", "walk", "bicycle");

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void selectedNameResolvesToTheSharedFactoryAndItsSummaryListener() throws Exception {
		Injector injector = controller(dmcConfig -> {
			Set<String> constraints = new HashSet<>(dmcConfig.getTripConstraints());
			constraints.add(BraunschweigModeChoiceModule.SAME_FACILITY_LEISURE_TRIP_CONSTRAINT_NAME);
			dmcConfig.setTripConstraints(constraints);
		}).getInjector();

		Provider<TripConstraintFactory> provider = tripConstraintFactories(injector)
				.get(BraunschweigModeChoiceModule.SAME_FACILITY_LEISURE_TRIP_CONSTRAINT_NAME);

		assertNotNull(provider);
		assertTrue(provider.get() instanceof SameFacilityLeisureTripConstraint.Factory);
		// One shared instance: the counters the mode choice fills are the ones the summary listener reports.
		assertSame(provider.get(), provider.get());
		assertSame(provider.get(), injector.getInstance(SameFacilityLeisureTripConstraint.Factory.class));
		assertTrue(listeners(injector).stream().anyMatch(LeisureTripRuleSummaryListener.class::isInstance));

		// The tour constraint chain the tour-based model uses asks the rule (through FromTripBased).
		List<DiscreteModeChoiceTrip> trips = strollFromHome();
		TourConstraint chain = injector.getInstance(TourConstraintFactory.class).createConstraint(person(), trips,
				MODES);
		SameFacilityLeisureTripConstraint.Factory factory = injector
				.getInstance(SameFacilityLeisureTripConstraint.Factory.class);

		assertEquals(2, factory.getCaughtTrips());
		assertTrue(chain.validateBeforeEstimation(trips, List.of("walk", "walk"), List.of()));
		assertFalse(chain.validateBeforeEstimation(trips, List.of("car", "car"), List.of()));
	}

	@Test
	public void notSelectedByDefaultAndThenUnused() throws Exception {
		Controler controller = controller(dmcConfig -> {
		});
		DiscreteModeChoiceConfigGroup dmcConfig = DiscreteModeChoiceConfigGroup.getOrCreate(controller.getConfig());

		assertFalse(dmcConfig.getTripConstraints()
				.contains(BraunschweigModeChoiceModule.SAME_FACILITY_LEISURE_TRIP_CONSTRAINT_NAME));
		assertFalse(dmcConfig.getTourConstraints()
				.contains(BraunschweigModeChoiceModule.SAME_FACILITY_LEISURE_TRIP_CONSTRAINT_NAME));

		// The tour constraint chain of the default config never asks the rule and leaves the car stroll alone.
		Injector injector = controller.getInjector();
		List<DiscreteModeChoiceTrip> trips = strollFromHome();
		TourConstraint chain = injector.getInstance(TourConstraintFactory.class).createConstraint(person(), trips,
				MODES);

		assertTrue(chain.validateBeforeEstimation(trips, List.of("car", "car"), List.of()));
		assertEquals(0, injector.getInstance(SameFacilityLeisureTripConstraint.Factory.class).getTrips());
	}

	/** A stroll placed at home: home -> leisure -> home, all at the home facility, by car in the diary. */
	private static List<DiscreteModeChoiceTrip> strollFromHome() {
		Id<ActivityFacility> home = Id.create("home", ActivityFacility.class);
		Activity start = PopulationUtils.createActivityFromCoord("home", new Coord(0, 0));
		Activity stroll = PopulationUtils.createActivityFromCoord("leisure", new Coord(0, 0));
		Activity end = PopulationUtils.createActivityFromCoord("home", new Coord(0, 0));

		for (Activity activity : List.of(start, stroll, end)) {
			activity.setFacilityId(home);
		}

		return List.of(
				new DiscreteModeChoiceTrip(start, stroll, "car", List.of(), 0, 0, 0, new AttributesImpl()),
				new DiscreteModeChoiceTrip(stroll, end, "car", List.of(), 0, 1, 1, new AttributesImpl()));
	}

	private static Person person() {
		return PopulationUtils.getFactory().createPerson(Id.createPersonId("person"));
	}

	private static Map<String, Provider<TripConstraintFactory>> tripConstraintFactories(Injector injector) {
		return injector.getInstance(Key.get(new TypeLiteral<Map<String, Provider<TripConstraintFactory>>>() {
		}));
	}

	private static Set<ControllerListener> listeners(Injector injector) {
		return injector.getInstance(Key.get(new TypeLiteral<Set<ControllerListener>>() {
		}));
	}

	/** The controller of a prepared scenario (empty population and network); nothing is injected yet. */
	private Controler controller(Consumer<DiscreteModeChoiceConfigGroup> dmcChange) throws Exception {
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
