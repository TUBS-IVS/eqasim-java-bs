package org.eqasim.braunschweig.mode_choice.utilities.estimators;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.eqasim.braunschweig.mode_choice.parameters.BraunschweigModeParameters;
import org.eqasim.braunschweig.mode_choice.utilities.variables.BraunschweigPersonVariables;
import org.eqasim.braunschweig.mode_choice.utilities.variables.BraunschweigPtVariables;
import org.eqasim.core.simulation.mode_choice.utilities.variables.CarVariables;
import org.junit.Test;

/**
 * Unit tests for the income-elastic monetary-cost utility (Task A1).
 *
 * The cost utility is multiplied by (income / referenceIncome)^lambdaCostIncome.
 * With lambdaCostIncome < 0, a given monetary cost weighs more (a more negative
 * utility) for a lower-income agent than for a higher-income agent at equal cost
 * and distance. These tests exercise the PRIMARY income path on both estimators
 * and assert the neutral fallback (missing income -> reference income -> factor 1).
 */
public class BraunschweigIncomeUtilityTest {
	private static BraunschweigPersonVariables person(double householdIncome_MU) {
		return new BraunschweigPersonVariables(false, false, false, householdIncome_MU);
	}

	private static CarVariables carTrip() {
		// travelTime_min, cost_MU, euclideanDistance_km, accessEgressTime_min
		return new CarVariables(20.0, 5.0, 10.0, 2.0);
	}

	private static BraunschweigPtVariables ptTrip() {
		return new BraunschweigPtVariables(20.0, 5.0, 2.0, 1, 10.0, false, false, false);
	}

	@Test
	public void carLowerIncomeHasMoreNegativeCostUtility() {
		BraunschweigModeParameters parameters = BraunschweigModeParameters.buildDefault();
		BraunschweigCarUtilityEstimator estimator = new BraunschweigCarUtilityEstimator(parameters, null, null);

		CarVariables variables = carTrip();
		double lowIncomeUtility = estimator.estimateMonetaryCostUtility(variables, person(1500.0));
		double highIncomeUtility = estimator.estimateMonetaryCostUtility(variables, person(6000.0));

		// betaCost is negative, so both utilities are negative; the lower-income
		// agent must be MORE negative (a unit of cost hurts more) at equal cost/distance.
		assertTrue("car cost utilities must be negative", lowIncomeUtility < 0.0 && highIncomeUtility < 0.0);
		assertTrue("lower-income car cost utility must be more negative than higher-income",
				lowIncomeUtility < highIncomeUtility);
	}

	@Test
	public void ptLowerIncomeHasMoreNegativeCostUtility() {
		BraunschweigModeParameters parameters = BraunschweigModeParameters.buildDefault();
		BraunschweigPtUtilityEstimator estimator = new BraunschweigPtUtilityEstimator(parameters, null, null, null);

		BraunschweigPtVariables variables = ptTrip();
		double cost_EUR = 5.0;
		double lowIncomeUtility = estimator.estimateMonetaryCostUtility(variables, cost_EUR, person(1500.0));
		double highIncomeUtility = estimator.estimateMonetaryCostUtility(variables, cost_EUR, person(6000.0));

		assertTrue("pt cost utilities must be negative", lowIncomeUtility < 0.0 && highIncomeUtility < 0.0);
		assertTrue("lower-income pt cost utility must be more negative than higher-income",
				lowIncomeUtility < highIncomeUtility);
	}

	@Test
	public void missingIncomeFallsBackToReferenceIncomeNeutralFactor() {
		BraunschweigModeParameters parameters = BraunschweigModeParameters.buildDefault();
		BraunschweigCarUtilityEstimator estimator = new BraunschweigCarUtilityEstimator(parameters, null, null);

		CarVariables variables = carTrip();
		// A missing income (NaN sentinel) must behave exactly like a person AT the
		// reference income: the income interaction factor is 1.0 (neutral).
		double missingIncomeUtility = estimator.estimateMonetaryCostUtility(variables,
				person(BraunschweigPredictorUtilsMissing()));
		double referenceIncomeUtility = estimator.estimateMonetaryCostUtility(variables,
				person(parameters.referenceHouseholdIncome_MU));

		assertEquals(referenceIncomeUtility, missingIncomeUtility, 1e-12);
	}

	@Test
	public void referenceIncomeYieldsNeutralIncomeFactor() {
		BraunschweigModeParameters parameters = BraunschweigModeParameters.buildDefault();
		BraunschweigCarUtilityEstimator estimator = new BraunschweigCarUtilityEstimator(parameters, null, null);

		CarVariables variables = carTrip();
		// At the reference income the income interaction is (1)^lambda = 1, so the
		// income-elastic utility equals the legacy distance-only cost utility.
		double withIncome = estimator.estimateMonetaryCostUtility(variables,
				person(parameters.referenceHouseholdIncome_MU));
		double legacyDistanceOnly = parameters.betaCost_u_MU
				* org.eqasim.core.simulation.mode_choice.utilities.estimators.EstimatorUtils.interaction(
						variables.euclideanDistance_km, parameters.referenceEuclideanDistance_km,
						parameters.lambdaCostEuclideanDistance)
				* variables.cost_MU;

		assertEquals(legacyDistanceOnly, withIncome, 1e-12);
	}

	private static double BraunschweigPredictorUtilsMissing() {
		return org.eqasim.braunschweig.mode_choice.utilities.predictors.BraunschweigPredictorUtils.HOUSEHOLD_INCOME_MISSING;
	}
}
