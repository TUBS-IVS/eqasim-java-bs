package org.eqasim.braunschweig.mode_choice.utilities.estimators;

import java.util.List;

import org.eqasim.core.simulation.mode_choice.utilities.UtilityEstimator;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;

/**
 * Constant-zero utility estimator for the freight "truck" mode. Freight agents
 * (german-wide-freight injection) only ever have "truck" available (see
 * {@link org.eqasim.braunschweig.mode_choice.BraunschweigModeAvailability}), so
 * the DMC choice among one option is trivial and its utility value is
 * irrelevant. Returning a constant 0.0 keeps the estimator well-defined without
 * pulling in any car/network predictors that do not apply to freight legs.
 */
public class FreightTruckUtilityEstimator implements UtilityEstimator {
	@Override
	public double estimateUtility(Person person, DiscreteModeChoiceTrip trip, List<? extends PlanElement> elements) {
		return 0.0;
	}
}
