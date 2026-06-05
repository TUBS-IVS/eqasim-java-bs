package org.eqasim.braunschweig.mode_choice.utilities.variables;

import org.eqasim.core.simulation.mode_choice.utilities.variables.BaseVariables;

public class BraunschweigCarPassengerVariables implements BaseVariables {
	final public double travelTime_min;
	final public double euclideanDistance_km;
	final public double accessEgressTime_min;

	public BraunschweigCarPassengerVariables(double travelTime_min, double euclideanDistance_km, double accessEgressTime_min) {
		this.travelTime_min = travelTime_min;
		this.euclideanDistance_km = euclideanDistance_km;
		this.accessEgressTime_min = accessEgressTime_min;
	}
}
