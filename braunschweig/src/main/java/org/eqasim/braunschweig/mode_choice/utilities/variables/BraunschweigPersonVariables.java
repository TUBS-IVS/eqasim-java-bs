package org.eqasim.braunschweig.mode_choice.utilities.variables;

import org.eqasim.core.simulation.mode_choice.utilities.variables.BaseVariables;

public class BraunschweigPersonVariables implements BaseVariables {
	public final boolean hasSubscription;
	public final boolean hasDrivingPermit;
	public final boolean isParisResident;

	// Monthly household income in monetary units (EUR). Consumed by the
	// income-elastic monetary-cost utility (Task A1): see
	// BraunschweigCarUtilityEstimator / BraunschweigPtUtilityEstimator.
	public final double householdIncome_MU;

	public BraunschweigPersonVariables(boolean hasSubscription, boolean hasDrivingPermit, boolean isParisResident,
			double householdIncome_MU) {
		this.hasSubscription = hasSubscription;
		this.hasDrivingPermit = hasDrivingPermit;
		this.isParisResident = isParisResident;
		this.householdIncome_MU = householdIncome_MU;
	}
}
