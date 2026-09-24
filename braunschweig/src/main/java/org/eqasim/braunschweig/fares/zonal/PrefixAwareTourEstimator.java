package org.eqasim.braunschweig.fares.zonal;

import java.util.ArrayList;
import java.util.List;

import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.DefaultTourCandidate;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourCandidate;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourEstimator;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripEstimator;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.core.utils.timing.TimeTracker;

import com.google.inject.Inject;

/**
 * Tour estimator with the native cumulative timing and utility sum, whose trip prefix also contains
 * the trip candidates of the already selected earlier tours of the plan. The DMC default passes only
 * the current tour's earlier trips; the VRB day-ticket cap (ADR-0133 D9) needs the whole day so far.
 * Carried over from the parked branch codex/regional-pt-fares (RegionalFareCumulativeTourEstimator).
 */
public final class PrefixAwareTourEstimator implements TourEstimator {
	private final TripEstimator delegate;
	private final TimeInterpretation timeInterpretation;

	@Inject
	public PrefixAwareTourEstimator(TripEstimator delegate, TimeInterpretation timeInterpretation) {
		this.delegate = delegate;
		this.timeInterpretation = timeInterpretation;
	}

	@Override
	public TourCandidate estimateTour(Person person, List<String> modes, List<DiscreteModeChoiceTrip> trips,
			List<TourCandidate> previousTours) {
		List<TripCandidate> prefix = new ArrayList<>();
		for (TourCandidate previousTour : previousTours) {
			prefix.addAll(previousTour.getTripCandidates());
		}
		List<TripCandidate> currentTour = new ArrayList<>();
		TimeTracker time = new TimeTracker(timeInterpretation);
		time.setTime(trips.getFirst().getDepartureTime());
		double utility = 0.0;
		for (int index = 0; index < modes.size(); index++) {
			DiscreteModeChoiceTrip trip = trips.get(index);
			if (index > 0) {
				time.addActivity(trip.getOriginActivity());
				trip.setDepartureTime(time.getTime().seconds());
			}
			TripCandidate candidate = delegate.estimateTrip(person, modes.get(index), trip, prefix);
			utility += candidate.getUtility();
			time.addDuration(candidate.getDuration());
			prefix.add(candidate);
			currentTour.add(candidate);
		}
		return new DefaultTourCandidate(utility, currentTour);
	}
}
