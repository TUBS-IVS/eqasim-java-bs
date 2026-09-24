package org.eqasim.braunschweig.fares.zonal;

import java.util.List;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;

/**
 * Utility correction of the VRB day-ticket cap (ADR-0133 D9) for one routed PT trip candidate whose
 * utility was estimated at its own single fare, possibly served from the DMC estimate cache. The
 * correction replaces that fare by the increase of the person's cheapest day cash over the trips
 * already in the day ({@code previousTrips}); it is zero when the cap does not bind.
 */
public interface DayTicketCapAdjustment {
	/** Utility difference (utils) to add to the candidate's utility; zero when the cap does not bind. */
	double utilityAdjustment(Person person, DiscreteModeChoiceTrip trip, List<? extends PlanElement> elements,
			List<TripCandidate> previousTrips);
}
