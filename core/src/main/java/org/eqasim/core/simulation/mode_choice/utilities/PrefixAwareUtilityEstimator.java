package org.eqasim.core.simulation.mode_choice.utilities;

import java.util.List;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;

/**
 * Utility estimator that needs the trip candidates DMC evaluated before the current trip (the
 * selected earlier tours and the current tour's prefix). {@link EqasimUtilityEstimator} passes them
 * when an estimator implements this interface; all other estimators keep the three-argument call.
 */
public interface PrefixAwareUtilityEstimator extends UtilityEstimator {
	double estimateUtility(Person person, DiscreteModeChoiceTrip trip, List<? extends PlanElement> elements,
			List<TripCandidate> previousTrips);
}
