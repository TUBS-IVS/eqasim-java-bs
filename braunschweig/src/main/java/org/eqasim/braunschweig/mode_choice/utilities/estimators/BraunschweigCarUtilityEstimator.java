package org.eqasim.braunschweig.mode_choice.utilities.estimators;

import java.util.List;

import org.eqasim.core.simulation.mode_choice.utilities.estimators.CarUtilityEstimator;
import org.eqasim.core.simulation.mode_choice.utilities.estimators.EstimatorUtils;
import org.eqasim.core.simulation.mode_choice.utilities.predictors.CarPredictor;
import org.eqasim.core.simulation.mode_choice.utilities.variables.CarVariables;
import org.eqasim.braunschweig.mode_choice.parameters.BraunschweigModeParameters;
import org.eqasim.braunschweig.mode_choice.utilities.predictors.BraunschweigPersonPredictor;
import org.eqasim.braunschweig.mode_choice.utilities.variables.BraunschweigPersonVariables;
import org.eqasim.braunschweig.parking.ParkingSearchTime;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;

import com.google.inject.Inject;

public class BraunschweigCarUtilityEstimator extends CarUtilityEstimator {
	private final BraunschweigModeParameters parameters;
	private final CarPredictor predictor;
	private final BraunschweigPersonPredictor personPredictor;
	private final ParkingSearchTime searchTime;

	/**
	 * @param searchTime parking search time of the destination zone (lever 4 of the zone-based parking costs); bound to
	 *        {@link ParkingSearchTime#none()} when the braunschweigParking module is absent, which leaves the utility
	 *        exactly as without the term
	 */
	@Inject
	public BraunschweigCarUtilityEstimator(BraunschweigModeParameters parameters, CarPredictor predictor,
			BraunschweigPersonPredictor personPredictor, ParkingSearchTime searchTime) {
		super(parameters, predictor);

		this.parameters = parameters;
		this.predictor = predictor;
		this.personPredictor = personPredictor;
		this.searchTime = searchTime;
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

	/**
	 * Parking search time of the destination zone (lever 4 of the zone-based parking costs, eqasim-bs issue #436):
	 * {@code betaTravelTime_u_min * searchTimeFactor * search_time_min}, i.e. the minutes of the zone column, scaled by
	 * the config parameter braunschweigParking.searchTimeFactor, valued like car travel time. 0 with the default factor
	 * 0 (off) and without the parking module: then nothing about the destination is read, so the utility is the one
	 * of version 1. The global car.constantParkingSearchPenalty_min stays 0, so the zone column is the only source of
	 * search time. The term depends on the destination activity only (the person names the owner of a failing attribute in
	 * the message and nothing else) and lives here, not in a predictor: the cached car variables are not involved.
	 */
	protected double estimateParkingSearchTimeUtility(Person person, Activity destination) {
		double factor = searchTime.factor();
		if (factor == 0.0) {
			return 0.0;
		}
		return parameters.car.betaTravelTime_u_min * factor * searchTime.minutes(person, destination);
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
		utility += estimateParkingSearchTimeUtility(person, trip.getDestinationActivity());

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
