package org.eqasim.core.standalone_mode_choice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.eqasim.core.scenario.routing.PlanRouter;
import org.eqasim.core.simulation.mode_choice.filters.FilteredTripCounter;
import org.eqasim.core.simulation.mode_choice.filters.FilteredTripFinder;
import org.eqasim.core.simulation.mode_choice.filters.KeepFilteredTripsModel;
import org.eqasim.core.simulation.mode_choice.filters.KeptTripElements;
import org.eqasim.core.simulation.mode_choice.filters.OutsideFilter;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.contribs.discrete_mode_choice.components.tour_finder.PlanTourFinder;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceModel;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.DefaultTripCandidate;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup.CompressionType;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * A trip the standalone mode choice does not re-choose (its tour is rejected by a filter, here eqasim's
 * {@link OutsideFilter}) leaves the runner exactly as it came in: the same legs, routes and attributes, the
 * walk-fallback stamp included (eqasim-bs#678). On the 100 % replay of eqasim-bs run prod100_20261031 the runner had
 * re-routed such border trips: 760 pt trips that had fallen back to walk lost their stamp, and 67 routed pt trips became
 * walk-only. A trip that is re-chosen still gets the model's choice.
 */
public class StandaloneModeChoiceFilteredTripsTest {
	private static final String CHOSEN_MODE = TransportMode.bike;

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	private static FilteredTripFinder finder() {
		return FilteredTripFinder.forTours(new PlanTourFinder(), new OutsideFilter());
	}

	/**
	 * Like the contrib's tour-based model: the trips of a rejected tour keep their initial modes (as unrouted
	 * candidates, which the contrib's algorithm would re-insert as bare legs), every other trip gets
	 * {@link #CHOSEN_MODE}. With {@code respectFilter = false} it re-chooses the rejected trips as well, which the
	 * wrapper must refuse.
	 */
	private static final class TourModelLike implements DiscreteModeChoiceModel {
		private final boolean respectFilter;

		TourModelLike(boolean respectFilter) {
			this.respectFilter = respectFilter;
		}

		@Override
		public List<TripCandidate> chooseModes(Person person, List<DiscreteModeChoiceTrip> trips, Random random) {
			List<DiscreteModeChoiceTrip> filtered = finder().findFilteredTrips(person, trips);
			List<TripCandidate> candidates = new ArrayList<>();
			for (DiscreteModeChoiceTrip trip : trips) {
				boolean kept = respectFilter && filtered.contains(trip);
				candidates.add(new DefaultTripCandidate(0.0, kept ? trip.getInitialMode() : CHOSEN_MODE, 0.0));
			}
			return candidates;
		}
	}

	private static Activity activity(PopulationFactory factory, String type, double x, Double endTime) {
		Activity activity = factory.createActivityFromCoord(type, new Coord(x, 0.0));
		if (endTime != null) {
			activity.setEndTime(endTime);
		}
		return activity;
	}

	private static Leg leg(PopulationFactory factory, String mode, String routingMode) {
		Leg leg = factory.createLeg(mode);
		TripStructureUtils.setRoutingMode(leg, routingMode);
		leg.setRoute(RouteUtils.createGenericRouteImpl(Id.createLinkId("from"), Id.createLinkId("to")));
		return leg;
	}

	/**
	 * A border person: outside > work by a pt trip that fell back to walk (stamped), work > outside by a routed pt
	 * trip (walk, pt, walk with interaction activities).
	 */
	private static void addBorderPerson(Population population) {
		PopulationFactory factory = population.getFactory();
		Person person = factory.createPerson(Id.createPersonId("border"));
		Plan plan = factory.createPlan();

		plan.addActivity(activity(factory, "outside", 0.0, 7.0 * 3600.0));
		Leg fallback = leg(factory, TransportMode.walk, TransportMode.walk);
		fallback.getAttributes().putAttribute(PlanRouter.WALK_FALLBACK_ATTRIBUTE, TransportMode.pt);
		plan.addLeg(fallback);
		plan.addActivity(activity(factory, "work", 800.0, 16.0 * 3600.0));

		plan.addLeg(leg(factory, TransportMode.walk, TransportMode.pt));
		plan.addActivity(PopulationUtils.createStageActivityFromCoordLinkIdAndModePrefix(new Coord(900.0, 0.0), null,
				TransportMode.pt));
		plan.addLeg(leg(factory, TransportMode.pt, TransportMode.pt));
		plan.addActivity(PopulationUtils.createStageActivityFromCoordLinkIdAndModePrefix(new Coord(9000.0, 0.0), null,
				TransportMode.pt));
		plan.addLeg(leg(factory, TransportMode.walk, TransportMode.pt));
		plan.addActivity(activity(factory, "outside", 10000.0, null));

		person.addPlan(plan);
		population.addPerson(person);
	}

	/** A resident: home > work > home by car, re-chosen by the model. */
	private static void addResident(Population population) {
		PopulationFactory factory = population.getFactory();
		Person person = factory.createPerson(Id.createPersonId("resident"));
		Plan plan = factory.createPlan();
		plan.addActivity(activity(factory, "home", 0.0, 8.0 * 3600.0));
		plan.addLeg(leg(factory, TransportMode.car, TransportMode.car));
		plan.addActivity(activity(factory, "work", 5000.0, 17.0 * 3600.0));
		plan.addLeg(leg(factory, TransportMode.car, TransportMode.car));
		plan.addActivity(activity(factory, "home", 0.0, null));
		person.addPlan(plan);
		population.addPerson(person);
	}

	private void perform(Scenario scenario, int threads) throws Exception {
		perform(scenario, threads, true);
	}

	private void perform(Scenario scenario, int threads, boolean respectFilter) throws Exception {
		StandaloneModeChoiceConfigGroup configGroup = new StandaloneModeChoiceConfigGroup();
		String output = new File(temporaryFolder.getRoot(), "output_" + threads).getAbsolutePath();
		OutputDirectoryHierarchy hierarchy = new OutputDirectoryHierarchy(output, null,
				OverwriteFileSetting.deleteDirectoryIfExists, CompressionType.gzip);
		FilteredTripCounter counter = new FilteredTripCounter();
		new StandaloneModeChoicePerformer(
				() -> new KeepFilteredTripsModel(new TourModelLike(respectFilter), finder(), KeptTripElements.unchanged(),
						counter), counter,
				configGroup, scenario.getPopulation(), threads, 1L, hierarchy, scenario).run();
	}

	@Test
	public void tripsOfARejectedTourLeaveUnchangedAndOthersAreChosen() throws Exception {
		for (int threads : new int[] { 0, 2 }) {
			Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
			addBorderPerson(scenario.getPopulation());
			addResident(scenario.getPopulation());
			Plan border = scenario.getPopulation().getPersons().get(Id.createPersonId("border")).getSelectedPlan();
			List<PlanElement> before = new ArrayList<>(border.getPlanElements());

			perform(scenario, threads);

			// the border plan: the very same elements, so the same legs, routes and attributes
			List<PlanElement> after = border.getPlanElements();
			assertEquals("threads " + threads, before.size(), after.size());
			for (int index = 0; index < before.size(); index++) {
				assertSame("threads " + threads + ", element " + index, before.get(index), after.get(index));
			}
			Leg fallback = (Leg) after.get(1);
			assertEquals(TransportMode.walk, fallback.getMode());
			assertEquals(TransportMode.pt, fallback.getAttributes().getAttribute(PlanRouter.WALK_FALLBACK_ATTRIBUTE));

			// the resident: re-chosen as before the fix
			List<String> residentModes = new ArrayList<>();
			for (Leg leg : TripStructureUtils.getLegs(
					scenario.getPopulation().getPersons().get(Id.createPersonId("resident")).getSelectedPlan())) {
				residentModes.add(leg.getMode());
			}
			assertEquals("threads " + threads, List.of(CHOSEN_MODE, CHOSEN_MODE), residentModes);
		}
	}

	@Test
	public void aModelThatReChoosesARejectedTripFails() {
		// the finder and the model disagree (e.g. a filter that depends on departure times): fail, never mix silently
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		addBorderPerson(scenario.getPopulation());
		IllegalStateException error = assertThrows(IllegalStateException.class, () -> perform(scenario, 0, false));
		assertTrue(error.getMessage(), error.getMessage().contains("the filter and the model disagree"));
	}
}
