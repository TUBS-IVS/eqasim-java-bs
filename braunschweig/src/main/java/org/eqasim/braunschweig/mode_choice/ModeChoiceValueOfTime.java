package org.eqasim.braunschweig.mode_choice;

import org.eqasim.braunschweig.fares.zonal.LongDistanceFareRaptorCostCalculator;
import org.eqasim.braunschweig.fares.zonal.SurchargeValuation;
import org.eqasim.braunschweig.mode_choice.parameters.BraunschweigModeParameters;
import org.eqasim.braunschweig.mode_choice.utilities.predictors.BraunschweigPredictorUtils;
import org.eqasim.core.simulation.mode_choice.utilities.estimators.EstimatorUtils;
import org.matsim.api.core.v01.population.Person;

/**
 * The long-distance flat price in PT in-vehicle seconds at the value of time the Braunschweig PT utility
 * applies to this person and trip (ADR-0133 D6): beta_time / (beta_cost x distance interaction x income
 * interaction), with the same interaction terms as BraunschweigPtUtilityEstimator.estimateMonetaryCostUtility.
 * A person without a numeric household income is valued at the reference income, as in the estimator.
 * The household income is read directly, so routing does not touch the estimator's income counters.
 */
public final class ModeChoiceValueOfTime implements SurchargeValuation {
	private final BraunschweigModeParameters parameters;
	private final double referenceSeconds;

	public ModeChoiceValueOfTime(long fareCents, BraunschweigModeParameters parameters) {
		this.parameters = parameters;
		this.referenceSeconds = LongDistanceFareRaptorCostCalculator.surchargeSeconds(fareCents,
				parameters.pt.betaInVehicleTime_u_min, parameters.betaCost_u_MU);
	}

	@Override
	public double surchargeSeconds(Person person, double tripDistanceKm) {
		Object income = person == null ? null
				: person.getAttributes().getAttribute(BraunschweigPredictorUtils.HOUSEHOLD_INCOME_ATTRIBUTE);
		double income_MU = income instanceof Number number ? number.doubleValue() : parameters.referenceHouseholdIncome_MU;
		// The surcharge scales with the cost weight: a smaller cost weight (longer trip, higher income) means a
		// higher value of time and fewer equivalent minutes.
		double costWeight = EstimatorUtils.interaction(tripDistanceKm, parameters.referenceEuclideanDistance_km,
				parameters.lambdaCostEuclideanDistance)
				* EstimatorUtils.interaction(income_MU, parameters.referenceHouseholdIncome_MU, parameters.lambdaCostIncome);
		return referenceSeconds * costWeight;
	}

	@Override
	public double referenceSurchargeSeconds() {
		return referenceSeconds;
	}
}
