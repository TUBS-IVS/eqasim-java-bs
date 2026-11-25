package org.eqasim.core.standalone_mode_choice;

import java.util.ArrayList;
import java.util.List;

import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.components.tour_finder.TourFinder;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourFilter;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TripFilter;

/**
 * Finds the trips the discrete mode choice model does not re-choose because a filter rejects them (for example
 * eqasim's {@code OutsideFilter} for tours that start or end outside the simulated area, or the tour length filter).
 *
 * <p>
 * The model keeps such trips at their initial modes, but it still re-estimates them, and
 * {@code DiscreteModeChoiceAlgorithm} re-inserts every trip it gets back. The trips of a rejected tour are therefore
 * re-routed by the plain trip router, which drops eqasim's walk-fallback stamp ({@code PlanRouter.WALK_FALLBACK_ATTRIBUTE})
 * and can turn a routed pt trip into a walk-only one (eqasim-bs#678). The standalone mode choice uses this finder to put
 * these trips back exactly as they came in. It applies the same tour finder and filter the model applies to the same
 * trip list, so it rejects exactly the tours the model rejects; the filters only look at the activities of a tour.
 * </p>
 */
public interface FilteredTripFinder {
	/** Returns the trips (of {@code trips}) that the model keeps at their initial modes because a filter rejects them. */
	List<DiscreteModeChoiceTrip> findFilteredTrips(Person person, List<DiscreteModeChoiceTrip> trips);

	/** For the tour-based models: the trips of every tour the tour filter rejects. */
	static FilteredTripFinder forTours(TourFinder tourFinder, TourFilter tourFilter) {
		return (person, trips) -> {
			List<DiscreteModeChoiceTrip> filtered = new ArrayList<>();
			for (List<DiscreteModeChoiceTrip> tour : tourFinder.findTours(trips)) {
				if (!tourFilter.filter(person, tour)) {
					filtered.addAll(tour);
				}
			}
			return filtered;
		};
	}

	/** For the trip-based model: every trip the trip filter rejects. */
	static FilteredTripFinder forTrips(TripFilter tripFilter) {
		return (person, trips) -> {
			List<DiscreteModeChoiceTrip> filtered = new ArrayList<>();
			for (DiscreteModeChoiceTrip trip : trips) {
				if (!tripFilter.filter(person, trip)) {
					filtered.add(trip);
				}
			}
			return filtered;
		};
	}
}
