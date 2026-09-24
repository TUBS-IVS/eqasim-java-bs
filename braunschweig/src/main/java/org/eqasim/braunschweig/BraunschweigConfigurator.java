package org.eqasim.braunschweig;

import java.util.ArrayList;

import org.eqasim.braunschweig.fares.zonal.VrbFareConfigGroup;
import org.eqasim.braunschweig.mode_choice.BraunschweigModeChoiceModule;
import org.eqasim.core.components.config.EqasimConfigGroup;
import org.eqasim.core.simulation.EqasimConfigurator;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.Config;

public class BraunschweigConfigurator extends EqasimConfigurator {
	public BraunschweigConfigurator(CommandLine cmd) {
		super(cmd);

		registerConfigGroup(new VrbFareConfigGroup(), true);
		registerModule(new BraunschweigModeChoiceModule(cmd));
	}

	/**
	 * With an enabled vrbFare module (ADR-0133), switch the pt cost model and estimator to the VRB zone
	 * fare ones and, for the day-ticket cap, serve pt utilities fresh (no DMC cache) through the
	 * prefix-aware tour estimator. Without the module the configuration is left exactly as before.
	 */
	@Override
	public void updateConfig(Config config) {
		VrbFareConfigGroup.promoteIfPresent(config);
		super.updateConfig(config);
		VrbFareConfigGroup fare = VrbFareConfigGroup.active(config);
		if (fare == null) {
			return;
		}
		fare.requireSupported();
		EqasimConfigGroup eqasim = EqasimConfigGroup.get(config);
		eqasim.setCostModel(TransportMode.pt, BraunschweigModeChoiceModule.VRB_FARE_PT_COST_MODEL_NAME);
		eqasim.setEstimator(TransportMode.pt, BraunschweigModeChoiceModule.VRB_FARE_PT_ESTIMATOR_NAME);
		if (fare.isDayTicketCapEnabled()) {
			DiscreteModeChoiceConfigGroup dmc = (DiscreteModeChoiceConfigGroup) config.getModules()
					.get(DiscreteModeChoiceConfigGroup.GROUP_NAME);
			if (dmc == null) {
				throw new IllegalStateException("vrbFare.dayTicketCapEnabled requires the discrete mode choice module");
			}
			// A prefix-dependent PT utility must not be served from the per-mode estimate cache.
			ArrayList<String> cached = new ArrayList<>(dmc.getCachedModes());
			cached.removeIf(TransportMode.pt::equals);
			dmc.setCachedModes(cached);
			dmc.setTourEstimator(BraunschweigModeChoiceModule.PREFIX_AWARE_TOUR_ESTIMATOR_NAME);
		}
	}
}
