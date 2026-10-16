package org.eqasim.braunschweig;


import org.eqasim.braunschweig.fares.zonal.VrbFareConfigGroup;
import org.eqasim.braunschweig.mode_choice.BraunschweigModeChoiceModule;
import org.eqasim.braunschweig.parking.ParkingConfigGroup;
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
		registerConfigGroup(new ParkingConfigGroup(), true);
		registerModule(new BraunschweigModeChoiceModule(cmd));
	}

	/**
	 * Applies the optional Braunschweig modules to the eqasim names; each one only when it is enabled, so
	 * without them the configuration is left exactly as before. The two are independent: vrbFare switches
	 * pt, braunschweigParking switches the car cost model.
	 */
	@Override
	public void updateConfig(Config config) {
		super.updateConfig(config);
		applyVrbFare(config);
		applyZoneParking(config);
	}

	/**
	 * With an enabled vrbFare module (ADR-0133), switch the pt cost model and estimator to the VRB zone
	 * fare ones and, for the day-ticket cap, the DMC tour estimator to the cap-correcting one. The pt
	 * estimate cache stays: the cap is a utility correction applied per mode chain in the tour estimator.
	 */
	private static void applyVrbFare(Config config) {
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

	/**
	 * With an enabled braunschweigParking module (design "parking cost zones", section 3.6; eqasim-bs issue
	 * #436), switch the car cost model to the zone parking one: driving cost plus the zone parking tariff.
	 * The estimators stay, so the car utility applies its monetary coefficient to the new cost unchanged.
	 */
	private static void applyZoneParking(Config config) {
		ParkingConfigGroup parking = ParkingConfigGroup.active(config);
		if (parking == null) {
			return;
		}
		parking.requireSupported();
		EqasimConfigGroup.get(config).setCostModel(TransportMode.car,
				BraunschweigModeChoiceModule.ZONE_PARKING_CAR_COST_MODEL_NAME);
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
