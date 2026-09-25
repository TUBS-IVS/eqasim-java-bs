package org.eqasim.braunschweig.mode_choice;

import org.eqasim.braunschweig.fares.zonal.LongDistanceFareRaptorCostCalculator;
import org.eqasim.braunschweig.fares.zonal.PtLineScopes;
import org.eqasim.braunschweig.fares.zonal.VrbFareModel;
import org.eqasim.braunschweig.mode_choice.parameters.BraunschweigModeParameters;
import org.matsim.api.core.v01.Scenario;

import com.google.inject.Inject;
import com.google.inject.Provider;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import ch.sbb.matsim.routing.pt.raptor.RaptorInVehicleCostCalculator;

/**
 * Builds the long-distance routing surcharge (ADR-0133 D6) from the fare model's flat price and the
 * mode choice's value of time (PT in-vehicle time and cost utilities of the Braunschweig mode
 * parameters). Bound in place of SwissRailRaptorModule's default in-vehicle cost only when the vrbFare
 * module enables it.
 */
public final class VrbLongDistanceRoutingCostProvider implements Provider<RaptorInVehicleCostCalculator> {
	private final Scenario scenario;
	private final PtLineScopes lineScopes;
	private final VrbFareModel fareModel;
	private final BraunschweigModeParameters parameters;
	private final SwissRailRaptorConfigGroup raptorConfig;

	@Inject
	public VrbLongDistanceRoutingCostProvider(Scenario scenario, PtLineScopes lineScopes, VrbFareModel fareModel,
			BraunschweigModeParameters parameters, SwissRailRaptorConfigGroup raptorConfig) {
		this.scenario = scenario;
		this.lineScopes = lineScopes;
		this.fareModel = fareModel;
		this.parameters = parameters;
		this.raptorConfig = raptorConfig;
	}

	@Override
	public RaptorInVehicleCostCalculator get() {
		// The surcharge extends the default in-vehicle cost; the capacity-dependent one is not wrapped.
		if (raptorConfig.isUseCapacityConstraints()) {
			throw new IllegalStateException("vrbFare.longDistanceRoutingSurchargeEnabled supports SwissRailRaptor without"
					+ " capacity constraints only; set one of the two to false");
		}
		double surchargeSeconds = LongDistanceFareRaptorCostCalculator.surchargeSeconds(
				fareModel.longDistanceSingleCents(), parameters.pt.betaInVehicleTime_u_min, parameters.betaCost_u_MU);
		return LongDistanceFareRaptorCostCalculator.create(scenario.getTransitSchedule(), scenario.getTransitVehicles(),
				lineScopes, surchargeSeconds, fareModel.childMinimumAge());
	}
}
