package org.eqasim.braunschweig.fares.zonal;

import java.util.ArrayList;
import java.util.List;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.DefaultTourCandidate;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourCandidate;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourEstimator;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripEstimator;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.DefaultRoutedTripCandidate;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.RoutedTripCandidate;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.core.utils.timing.TimeTracker;

import com.google.inject.Inject;

/**
 * Tour estimator for the VRB day-ticket cap (ADR-0133 D9): the native cumulative timing and utility sum,
 * plus the cap correction of every routed PT trip candidate. The trip prefix handed to the trip estimator
 * and to the correction also contains the trip candidates of the already selected earlier tours of the
 * plan; the DMC default passes only the current tour's earlier trips, and the cap needs the day so far.
 *
 * <p>The PT utility itself is estimated at the trip's own single fare, so it can stay in the DMC estimate
 * cache (no re-routing per mode chain); only the cheap correction depends on the chain. The prefix
 * handling is carried over from the parked branch codex/regional-pt-fares (RegionalFareCumulativeTourEstimator).
 */
public final class DayTicketCapTourEstimator implements TourEstimator {
	private final TripEstimator delegate;
	private final TimeInterpretation timeInterpretation;
	private final DayTicketCapAdjustment cap;

	@Inject
	public DayTicketCapTourEstimator(TripEstimator delegate, TimeInterpretation timeInterpretation,
			DayTicketCapAdjustment cap) {
		this.delegate = delegate;
		this.timeInterpretation = timeInterpretation;
		this.cap = cap;
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
			if (TransportMode.pt.equals(candidate.getMode()) && candidate instanceof RoutedTripCandidate routed) {
				double correction = cap.utilityAdjustment(person, trip, routed.getRoutedPlanElements(), prefix);
				if (correction != 0.0) {
					// A new candidate, because the cached one is shared by every mode chain of this tour.
					candidate = new DefaultRoutedTripCandidate(candidate.getUtility() + correction, candidate.getMode(),
							routed.getRoutedPlanElements(), candidate.getDuration());
				}
			}
			utility += candidate.getUtility();
			time.addDuration(candidate.getDuration());
			prefix.add(candidate);
			currentTour.add(candidate);
		}
		return new DefaultTourCandidate(utility, currentTour);
	}
}
