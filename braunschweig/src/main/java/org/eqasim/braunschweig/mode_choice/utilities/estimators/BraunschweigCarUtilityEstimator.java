package org.eqasim.braunschweig.mode_choice.utilities.estimators;

import java.util.List;

import org.eqasim.core.simulation.mode_choice.utilities.estimators.CarUtilityEstimator;
import org.eqasim.core.simulation.mode_choice.utilities.estimators.EstimatorUtils;
import org.eqasim.core.simulation.mode_choice.utilities.predictors.CarPredictor;
import org.eqasim.core.simulation.mode_choice.utilities.variables.CarVariables;
import org.eqasim.braunschweig.mode_choice.parameters.BraunschweigModeParameters;
import org.eqasim.braunschweig.mode_choice.utilities.predictors.BraunschweigPersonPredictor;
import org.eqasim.braunschweig.mode_choice.utilities.variables.BraunschweigPersonVariables;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;

import com.google.inject.Inject;

public class BraunschweigCarUtilityEstimator extends CarUtilityEstimator {
	private final BraunschweigModeParameters parameters;
	private final CarPredictor predictor;
	private final BraunschweigPersonPredictor personPredictor;

	@Inject
	public BraunschweigCarUtilityEstimator(BraunschweigModeParameters parameters, CarPredictor predictor,
			BraunschweigPersonPredictor personPredictor) {
		super(parameters, predictor);

		this.parameters = parameters;
		this.predictor = predictor;
		this.personPredictor = personPredictor;
	}

	protected double estimateAccessEgressTimeUtility(CarVariables variables) {
		return parameters.betaAccessTime_u_min * variables.accessEgressTime_min;
	}

	/**
	 * Income-elastic monetary-cost utility (Task A1). On top of the existing
	 * cost-vs-distance interaction (kept intact in the superclass), the cost is
	 * multiplied by (income/referenceIncome)^lambdaCostIncome -- the canonical
	 * eqasim income elasticity. A person with a missing income attribute uses the
	 * reference income, so its income interaction term is exactly 1.0 (neutral).
	 */
	protected double estimateMonetaryCostUtility(CarVariables variables, BraunschweigPersonVariables personVariables) {
		double income_MU = Double.isNaN(personVariables.householdIncome_MU) ? parameters.referenceHouseholdIncome_MU
				: personVariables.householdIncome_MU;
		return super.estimateMonetaryCostUtility(variables) * EstimatorUtils.interaction(income_MU,
				parameters.referenceHouseholdIncome_MU, parameters.lambdaCostIncome);
	}

	@Override
	public double estimateUtility(Person person, DiscreteModeChoiceTrip trip, List<? extends PlanElement> elements) {
		CarVariables variables = predictor.predictVariables(person, trip, elements);
		BraunschweigPersonVariables personVariables = personPredictor.predictVariables(person, trip, elements);

		double utility = 0.0;

		utility += estimateConstantUtility();
		utility += estimateTravelTimeUtility(variables);
		utility += estimateAccessEgressTimeUtility(variables);
		utility += estimateMonetaryCostUtility(variables, personVariables);

		if (isParis(trip)) {
			utility += parameters.munich.car_u;
		}

		return utility;
	}

	static private boolean isParis(DiscreteModeChoiceTrip trip) {
		return isParis(trip.getOriginActivity()) || isParis(trip.getDestinationActivity());
	}

	static private boolean isParis(Activity activity) {
		Boolean isParis = (Boolean) activity.getAttributes().getAttribute("isParis");
		return isParis != null && isParis;
	}
}
