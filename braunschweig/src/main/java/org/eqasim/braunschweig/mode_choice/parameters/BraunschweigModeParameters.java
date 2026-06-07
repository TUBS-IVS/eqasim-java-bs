package org.eqasim.braunschweig.mode_choice.parameters;

import org.eqasim.core.simulation.mode_choice.parameters.ModeParameters;

public class BraunschweigModeParameters extends ModeParameters {
	public class MunichParameters {
		public double car_u;
		public double carPassenger_u;
		public double bicycle_u;
	}

	public final MunichParameters munich = new MunichParameters();

	public class BraunschweigCarPassengerParameters {
		public double alpha_u;
		public double betaInVehicleTravelTime_u_min;
		public double betaDrivingPermit_u;
	}

	public final BraunschweigCarPassengerParameters carPassenger = new BraunschweigCarPassengerParameters();

	public class BraunschweigPtParameters {
		public double betaDrivingPermit_u;
		public double onlyBus_u;
	}

	public final BraunschweigPtParameters braunschweigPt = new BraunschweigPtParameters();

	public double betaAccessTime_u_min;

	// Income elasticity of the monetary-cost utility (Task A1). The cost term is
	// multiplied by (householdIncome / referenceHouseholdIncome)^lambdaCostIncome,
	// the canonical eqasim form (see ZurichModeParameters). A negative lambda makes
	// a given monetary cost weigh less for higher-income agents (and more for
	// lower-income agents), so the synthesised household income drives mode choice.
	public double lambdaCostIncome;
	public double referenceHouseholdIncome_MU;

	public static BraunschweigModeParameters buildDefault() {
		BraunschweigModeParameters parameters = new BraunschweigModeParameters();

		// Access
		parameters.betaAccessTime_u_min = -0.031239;

		// Cost
		parameters.betaCost_u_MU = -0.310998;
		parameters.lambdaCostEuclideanDistance = -0.257501;
		parameters.referenceEuclideanDistance_km = 4.4;

		// Income elasticity of cost (Task A1). lambdaCostIncome is transferred from the
		// eqasim Switzerland estimation (ZurichModeParameters.buildFrom6Feb2020:
		// lambdaCostHouseholdIncome = -0.8169), the canonical eqasim income elasticity;
		// no local re-estimation exists yet (Task B1, deferred). referenceHouseholdIncome_MU
		// is the synthetic-population mean monthly household income in EUR -- a provisional
		// value pending the mean logged by the next full synthesis run (the legacy incommuter
		// income constant was 3000 EUR/month; ZGB net household income is of this order).
		// With income == reference the interaction term is exactly 1.0, so this is a neutral
		// reference point, not an additive offset.
		parameters.lambdaCostIncome = -0.8169;
		parameters.referenceHouseholdIncome_MU = 3200.0;

		// Car
		parameters.car.alpha_u = 0.4; // -0.201465;
		parameters.car.betaTravelTime_u_min = -0.042431;

		// Car passenger
		parameters.carPassenger.alpha_u = -1.4; // -1.713201;
		parameters.carPassenger.betaDrivingPermit_u = -0.835542;
		parameters.carPassenger.betaInVehicleTravelTime_u_min = -0.069976;

		// PT
		parameters.pt.alpha_u = 0.0;
		parameters.pt.betaLineSwitch_u = -0.417658;
		parameters.pt.betaInVehicleTime_u_min = -0.025501;
		parameters.pt.betaWaitingTime_u_min = -0.021801;

		parameters.braunschweigPt.betaDrivingPermit_u = -0.531426;
		parameters.braunschweigPt.onlyBus_u = -1.416309;

		// Bike
		parameters.bike.alpha_u = -0.5; // -2.927596;
		parameters.bike.betaTravelTime_u_min = -0.093485;

		// Walk
		parameters.walk.alpha_u = 1.8; // 1.685152;
		parameters.walk.betaTravelTime_u_min = -0.162285;

		return parameters;
	}
}
