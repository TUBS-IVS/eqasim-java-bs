package org.eqasim.braunschweig.mode_choice.utilities.predictors;

import java.util.List;

import org.eqasim.core.simulation.mode_choice.utilities.predictors.CachedVariablePredictor;
import org.eqasim.braunschweig.mode_choice.utilities.variables.BraunschweigPersonVariables;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;

public class BraunschweigPersonPredictor extends CachedVariablePredictor<BraunschweigPersonVariables> {
	@Override
	protected BraunschweigPersonVariables predict(Person person, DiscreteModeChoiceTrip trip,
			List<? extends PlanElement> elements) {
		boolean hasSubscription = BraunschweigPredictorUtils.hasSubscription(person);
		boolean hasDrivingPermit = BraunschweigPredictorUtils.hasDrivingLicense(person);
		boolean isParisResident = BraunschweigPredictorUtils.isParisResident(person);
		return new BraunschweigPersonVariables(hasSubscription, hasDrivingPermit, isParisResident);
	}
}
