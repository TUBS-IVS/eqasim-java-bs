package org.eqasim.core.standalone_mode_choice;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.io.FileUtils;
import org.eqasim.TestConfigurator;
import org.eqasim.core.components.config.EqasimConfigGroup;
import org.eqasim.core.components.raptor.EqasimRaptorConfigGroup;
import org.eqasim.core.misc.InjectorBuilder;
import org.eqasim.core.scenario.config.GenerateConfig;
import org.eqasim.core.simulation.EqasimConfigurator;
import org.eqasim.core.simulation.mode_choice.AbstractEqasimExtension;
import org.eqasim.core.simulation.mode_choice.EqasimModeChoiceModule;
import org.eqasim.core.simulation.mode_choice.tour_finder.ActivityTourFinderWithExcludedActivities;
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
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.contribs.discrete_mode_choice.components.tour_finder.ActivityTourFinder;
import org.matsim.contribs.discrete_mode_choice.components.tour_finder.TourFinder;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourFilter;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TripFilter;
import org.matsim.contribs.discrete_mode_choice.modules.FilterModule;
import org.matsim.contribs.discrete_mode_choice.modules.ModelModule.ModelType;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.contribs.discrete_mode_choice.replanning.TripListConverter;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.timing.TimeInterpretationModule;

import com.google.inject.Injector;

/**
 * {@link StandaloneModeChoiceModule#provideFilteredTripFinder} builds the finder from the DMC bindings the config
 * selects (eqasim-bs#678): the tour finder {@code IsolatedOutsideTrips} (Braunschweig binds this name to
 * {@link ActivityTourFinderWithExcludedActivities} on {@code [home, outside]} with {@code outside} excluded; the same
 * composition is bound here, the Braunschweig provider is pinned by its IsolatedOutsideTripsContractTest) and the tour
 * filters {@code OutsideFilter} and {@code TourLength}. The finder must return exactly the border trips and the trips of
 * the over-long tour.
 */
public class FilteredTripFinderWiringTest {
	private static final String ISOLATED_OUTSIDE_TRIPS = "IsolatedOutsideTrips";
	private static final int MAXIMUM_TOUR_LENGTH = 3;

	@After
	public void tearDown() throws IOException {
		FileUtils.deleteDirectory(new File("simulation_output"));
	}

	private static Injector createInjector(ModelType modelType) throws ConfigurationException {
		// The generated eqasim configuration, as in TripEstimatorBindingTest
		Config config = ConfigUtils.createConfig();
		new GenerateConfig(new CommandLine.Builder(new String[] {}).build(), "", 1.0, 1, 1).run(config);
		config.addModule(new EqasimRaptorConfigGroup());
		config.removeModule(EqasimTerminationConfigGroup.GROUP_NAME);
		EqasimConfigGroup.get(config).setEstimator("walk", EqasimModeChoiceModule.ZERO_ESTIMATOR_NAME);
		EqasimConfigGroup.get(config).setEstimator("bike", EqasimModeChoiceModule.ZERO_ESTIMATOR_NAME);

		DiscreteModeChoiceConfigGroup dmcConfig = (DiscreteModeChoiceConfigGroup) config.getModules()
				.get(DiscreteModeChoiceConfigGroup.GROUP_NAME);
		dmcConfig.setModelType(modelType);
		dmcConfig.setTourFinder(ISOLATED_OUTSIDE_TRIPS);
		dmcConfig.getActivityTourFinderConfigGroup().setActivityTypes(List.of("home", "outside"));
		dmcConfig.setTourFilters(List.of(EqasimModeChoiceModule.OUTSIDE_FILTER_NAME, FilterModule.TOUR_LENGTH));
		dmcConfig.getTourLengthFilterConfigGroup().setMaximumLength(MAXIMUM_TOUR_LENGTH);
		dmcConfig.setTripFilters(List.of());

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
				.addOverridingModule(new AbstractEqasimExtension() {
					@Override
					protected void installEqasimExtension() {
						bindTourFinder(ISOLATED_OUTSIDE_TRIPS)
								.toInstance(new ActivityTourFinderWithExcludedActivities(List.of("outside"),
										new ActivityTourFinder(List.of("home", "outside"))));
					}
				}).build();
	}

	private static FilteredTripFinder finder(Injector injector) {
		return new StandaloneModeChoiceModule().provideFilteredTripFinder(
				injector.getInstance(DiscreteModeChoiceConfigGroup.class), injector.getProvider(TourFinder.class),
				injector.getProvider(TourFilter.class), injector.getProvider(TripFilter.class));
	}

	/** A plan through the activity types (H home, O outside, other letters inside), one walk trip between each two. */
	private static Plan plan(String activities) {
		PopulationFactory factory = PopulationUtils.getFactory();
		Person person = factory.createPerson(Id.createPersonId("person"));
		Plan plan = factory.createPlan();
		for (int index = 0; index < activities.length(); index++) {
			char letter = activities.charAt(index);
			String type = letter == 'H' ? "home" : letter == 'O' ? "outside" : "other";
			Activity activity = factory.createActivityFromCoord(type, new Coord(100.0 * index, 0.0));
			if (index < activities.length() - 1) {
				activity.setEndTime(6.0 * 3600.0 + 3600.0 * index);
				plan.addActivity(activity);
				Leg leg = factory.createLeg(TransportMode.walk);
				TripStructureUtils.setRoutingMode(leg, TransportMode.walk);
				plan.addLeg(leg);
			} else {
				plan.addActivity(activity);
			}
		}
		person.addPlan(plan);
		return plan;
	}

	private static Set<Integer> filteredTripIndices(FilteredTripFinder finder, String activities) {
		Plan plan = plan(activities);
		Set<Integer> indices = new TreeSet<>();
		for (DiscreteModeChoiceTrip trip : finder.findFilteredTrips(plan.getPerson(),
				new TripListConverter().convert(plan))) {
			indices.add(trip.getIndex());
		}
		return indices;
	}

	@Test
	public void theTourModelFinderRejectsTheBorderTripsAndTheOverLongTour() throws ConfigurationException {
		FilteredTripFinder finder = finder(createInjector(ModelType.Tour));

		// H > W > O > H: the inside run H > W stays open, the border trips W > O and O > H are rejected
		assertEquals(Set.of(1, 2), filteredTripIndices(finder, "HWOH"));
		// a home tour of 4 trips is longer than the limit of 3: all its trips are rejected; a tour of 2 stays open
		assertEquals(Set.of(0, 1, 2, 3), filteredTripIndices(finder, "HABCH"));
		assertEquals(Set.of(), filteredTripIndices(finder, "HAH"));
	}

	@Test
	public void theTripModelFinderUsesTheTripFilters() throws ConfigurationException {
		// no trip filter configured: the trip-based model re-chooses every trip, so nothing is kept
		FilteredTripFinder finder = finder(createInjector(ModelType.Trip));
		assertEquals(Set.of(), filteredTripIndices(finder, "HWOH"));
	}
}
