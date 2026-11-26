package org.eqasim.core.simulation.mode_choice.filters;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceModel;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.RoutedTripCandidate;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;

/**
 * Wraps the discrete mode choice model so that the trips it does not re-choose leave the mode choice exactly as they
 * came in (eqasim-bs#678).
 *
 * <p>
 * The contrib's {@code DiscreteModeChoiceAlgorithm} re-inserts every trip it gets back from the model: the routed
 * elements of a {@link RoutedTripCandidate}, else a bare leg. For a tour its filter rejects, the tour-based model keeps
 * the initial modes but re-estimates, i.e. re-routes, them with the plain trip router, which drops the walk-fallback
 * stamp and can turn routed pt into walk-only legs. This wrapper replaces the candidate of every trip
 * {@link FilteredTripFinder} reports by a routed candidate whose elements are the trip's own elements as they were in the
 * plan; the algorithm then puts the very same objects back. Utility, duration and mode come from the wrapped model's
 * candidate, so which trips are re-chosen, and how, does not change.
 * </p>
 *
 * <p>
 * The wrapped model must have kept a reported trip at its initial mode; otherwise the finder and the model disagree
 * (for example a filter that depends on departure times, see {@link FilteredTripFinder}) and the choice fails instead
 * of silently mixing the two. Every chosen trip and every kept trip is counted in the {@link FilteredTripCounter}.
 * </p>
 *
 * <p>
 * Used by the MATSim loop's mode choice replanning and by the standalone mode choice, which both obtain the model from
 * the injected {@code DiscreteModeChoiceModel} binding ({@link FilteredTripsModule}).
 * </p>
 */
public class KeepFilteredTripsModel implements DiscreteModeChoiceModel {
	private final DiscreteModeChoiceModel delegate;
	private final FilteredTripFinder filteredTripFinder;
	private final FilteredTripCounter counter;

	public KeepFilteredTripsModel(DiscreteModeChoiceModel delegate, FilteredTripFinder filteredTripFinder,
			FilteredTripCounter counter) {
		this.delegate = delegate;
		this.filteredTripFinder = filteredTripFinder;
		this.counter = counter;
	}

	@Override
	public List<TripCandidate> chooseModes(Person person, List<DiscreteModeChoiceTrip> trips, Random random)
			throws NoFeasibleChoiceException {
		List<TripCandidate> candidates = delegate.chooseModes(person, trips, random);
		List<DiscreteModeChoiceTrip> filteredTrips = filteredTripFinder.findFilteredTrips(person, trips);
		counter.add(trips.size(), filteredTrips.size());

		if (filteredTrips.isEmpty()) {
			return candidates;
		}

		List<TripCandidate> result = new ArrayList<>(candidates);
		for (DiscreteModeChoiceTrip filteredTrip : filteredTrips) {
			int index = indexOf(trips, filteredTrip);
			if (index < 0) {
				throw new IllegalStateException(String.format(
						"Person %s: a filtered trip from activity %s is not one of the trips of the choice",
						person.getId(), filteredTrip.getOriginActivity().getType()));
			}

			TripCandidate candidate = candidates.get(index);
			if (!candidate.getMode().equals(filteredTrip.getInitialMode())) {
				throw new IllegalStateException(String.format(
						"Person %s, trip %d: the filter rejects its tour, but the model chose %s instead of the initial "
								+ "mode %s; the filter and the model disagree (a filter must not depend on departure times)",
						person.getId(), index, candidate.getMode(), filteredTrip.getInitialMode()));
			}

			result.set(index, new KeptTripCandidate(candidate, new ArrayList<>(filteredTrip.getInitialElements())));
		}
		return result;
	}

	private static int indexOf(List<DiscreteModeChoiceTrip> trips, DiscreteModeChoiceTrip trip) {
		for (int index = 0; index < trips.size(); index++) {
			if (trips.get(index) == trip) {
				return index;
			}
		}
		return -1;
	}

	/** The model's candidate for a kept trip, routed with the trip's own elements as they were in the plan. */
	static final class KeptTripCandidate implements TripCandidate, RoutedTripCandidate {
		private final TripCandidate candidate;
		private final List<PlanElement> elements;

		KeptTripCandidate(TripCandidate candidate, List<PlanElement> elements) {
			this.candidate = candidate;
			this.elements = elements;
		}

		@Override
		public double getUtility() {
			return candidate.getUtility();
		}

		@Override
		public String getMode() {
			return candidate.getMode();
		}

		@Override
		public double getDuration() {
			return candidate.getDuration();
		}

		@Override
		public List<? extends PlanElement> getRoutedPlanElements() {
			return elements;
		}
	}
}
