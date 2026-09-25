package org.eqasim.braunschweig;


import org.eqasim.braunschweig.fares.zonal.VrbFareConfigGroup;
import org.eqasim.braunschweig.mode_choice.BraunschweigModeChoiceModule;
import org.eqasim.core.components.config.EqasimConfigGroup;
import org.eqasim.core.simulation.EqasimConfigurator;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contribs.discrete_mode_choice.modules.EstimatorModule;
import org.matsim.contribs.discrete_mode_choice.modules.ModelModule.ModelType;
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
	 * fare ones and, for the day-ticket cap, the DMC tour estimator to the cap-correcting one. The pt
	 * estimate cache stays: the cap is a utility correction applied per mode chain in the tour estimator.
	 * Without the module the configuration is left exactly as before.
	 */
	@Override
	public void updateConfig(Config config) {
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
			useDayTicketCapTourEstimator(config);
		}
	}

	private static void useDayTicketCapTourEstimator(Config config) {
		DiscreteModeChoiceConfigGroup dmc = (DiscreteModeChoiceConfigGroup) config.getModules()
				.get(DiscreteModeChoiceConfigGroup.GROUP_NAME);
		if (dmc == null) {
			throw new IllegalStateException("vrbFare.dayTicketCapEnabled requires the discrete mode choice module");
		}
		// A trip-based model never asks a tour estimator, so the cap would silently not apply.
		if (dmc.getModelType() != ModelType.Tour) {
			throw new IllegalStateException("vrbFare.dayTicketCapEnabled requires the tour-based DMC model, found "
					+ dmc.getModelType() + "; set vrbFare.dayTicketCapEnabled=false or use modelType Tour");
		}
		String tourEstimator = dmc.getTourEstimator();
		// The cap estimator reproduces the cumulative estimator; replacing any other one would change more than the fare.
		if (!EstimatorModule.CUMULATIVE.equals(tourEstimator)
				&& !BraunschweigModeChoiceModule.DAY_TICKET_CAP_TOUR_ESTIMATOR_NAME.equals(tourEstimator)) {
			throw new IllegalStateException("vrbFare.dayTicketCapEnabled replaces the " + EstimatorModule.CUMULATIVE
					+ " tour estimator, but the config uses " + tourEstimator);
		}
		dmc.setTourEstimator(BraunschweigModeChoiceModule.DAY_TICKET_CAP_TOUR_ESTIMATOR_NAME);
	}
}
