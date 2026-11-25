package org.eqasim.braunschweig.mode_choice;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eqasim.core.simulation.mode_choice.filters.OutsideFilter;
import org.eqasim.core.simulation.mode_choice.tour_finder.ActivityTourFinderWithExcludedActivities;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.components.filters.TourLengthFilter;
import org.matsim.contribs.discrete_mode_choice.components.tour_finder.ActivityTourFinder;
import org.matsim.contribs.discrete_mode_choice.components.tour_finder.TourFinder;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourFilter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.facilities.ActivityFacility;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

/**
 * Pins which trips the shipped tour finder {@code IsolatedOutsideTrips}
 * ({@link ActivityTourFinderWithExcludedActivities} on {@link ActivityTourFinder} with {@code [home, outside]})
 * together with the shipped {@link OutsideFilter} opens to the mode choice (eqasim-bs#678, spec section 7, cases 2 to
 * 5), and how the shipped {@link TourLengthFilter} counts the isolated runs. The Python mirror of this classification follows these sets; the core classes are used unchanged.
 */
public class IsolatedOutsideTripsContractTest {
	private static final List<String> TOUR_ACTIVITY_TYPES = List.of("home", "outside");

	private final TourFilter filter = new OutsideFilter();

	@Test
	public void portalStayInTheMiddle() {
		assertEquals(Set.of(0, 3), openTrips(isolatedFinder(), "HWOLH"));
	}

	@Test
	public void outsideDestinationBeforeHome() {
		assertEquals(Set.of(0), openTrips(isolatedFinder(), "HWOH"));
	}

	@Test
	public void insideTripCutInTwo() {
		assertEquals("outside", plan("HAOOBH").get(2).getInitialMode());
		assertEquals(Set.of(0, 4), openTrips(isolatedFinder(), "HAOOBH"));
	}

	@Test
	public void planNotStartingAtHome() {
		assertEquals(Set.of(0, 3), openTrips(isolatedFinder(), "LWOSH"));
	}

	@Test
	public void coarseFinderOpensNothing() {
		TourFinder coarse = new ActivityTourFinder(TOUR_ACTIVITY_TYPES);
		for (String plan : List.of("HWOLH", "HWOH", "HAOOBH", "LWOSH")) {
			assertEquals(plan, Set.of(), openTrips(coarse, plan));
		}
	}

	/**
	 * The shipped {@link TourLengthFilter} judges the isolated runs, not the whole border tour: a 12-trip border tour
	 * whose inside runs have 5 trips each is open under a limit of 6; an inside run of 7 trips is not.
	 */
	@Test
	public void lengthFilterJudgesTheIsolatedRun() {
		List<TourFilter> filters = List.of(filter, new TourLengthFilter(6));

		// Trips 0-4 inside, 5 (E->O) and 6 (O->F) at the border, 7-11 inside.
		String twelveTrips = "HABCDEOFGIJKH";
		assertEquals(12, twelveTrips.length() - 1);
		assertEquals(Set.of(0, 1, 2, 3, 4, 7, 8, 9, 10, 11), openTrips(isolatedFinder(), twelveTrips, filters));

		// Trips 0-6 inside (a run of 7), 7 (G->O) and 8 (O->I) at the border, 9 inside.
		assertEquals(Set.of(9), openTrips(isolatedFinder(), "HABCDEFGOIH", filters));
	}

	private static TourFinder isolatedFinder() {
		return new ActivityTourFinderWithExcludedActivities(List.of("outside"),
				new ActivityTourFinder(TOUR_ACTIVITY_TYPES));
	}

	/** Indices of the trips that lie in tours passing the filter. */
	private Set<Integer> openTrips(TourFinder finder, String activities) {
		return openTrips(finder, activities, List.of(filter));
	}

	/** Indices of the trips that lie in tours passing all filters. */
	private static Set<Integer> openTrips(TourFinder finder, String activities, List<TourFilter> filters) {
		Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId("person"));
		Set<Integer> open = new TreeSet<>();
		for (List<DiscreteModeChoiceTrip> tour : finder.findTours(plan(activities))) {
			if (filters.stream().allMatch(tourFilter -> tourFilter.filter(person, tour))) {
				tour.forEach(trip -> open.add(trip.getIndex()));
			}
		}
		return open;
	}

	/**
	 * One trip between consecutive activities; H = home, O = outside, other letters inside activities. A trip from
	 * outside to outside has mode {@code outside} (as in the synthetic population, spec section 7 case 4), every
	 * other trip walk.
	 */
	private static List<DiscreteModeChoiceTrip> plan(String activities) {
		List<DiscreteModeChoiceTrip> trips = new ArrayList<>();
		Activity origin = activity(activities.charAt(0), 0);
		for (int index = 1; index < activities.length(); index++) {
			Activity destination = activity(activities.charAt(index), index);
			boolean outsideToOutside = activities.charAt(index - 1) == 'O' && activities.charAt(index) == 'O';
			String mode = outsideToOutside ? "outside" : "walk";
			trips.add(new DiscreteModeChoiceTrip(origin, destination, mode, List.of(), 0, index - 1, index - 1,
					new AttributesImpl()));
			origin = destination;
		}
		return trips;
	}

	private static Activity activity(char letter, int position) {
		String type = switch (letter) {
		case 'H' -> "home";
		case 'O' -> "outside";
		default -> "other";
		};
		Activity activity = PopulationUtils.createActivityFromCoord(type, new Coord(position, 0));
		activity.setFacilityId(Id.create(letter == 'H' ? "home" : String.valueOf(letter), ActivityFacility.class));
		return activity;
	}
}
