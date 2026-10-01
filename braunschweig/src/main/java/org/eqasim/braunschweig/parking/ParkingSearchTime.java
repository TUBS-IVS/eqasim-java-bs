package org.eqasim.braunschweig.parking;

import java.util.Objects;

import org.matsim.api.core.v01.population.Activity;

import com.google.inject.Inject;

/**
 * Parking search time of a car trip destination (design "parking cost zones v2", lever 4; eqasim-bs issue #436): the
 * {@code search_time_min} column of the tariff model for the zone the destination lies in, and the factor
 * {@code braunschweigParking.searchTimeFactor} that scales it into the car utility. The utility term itself lives in
 * {@code BraunschweigCarUtilityEstimator}: {@code betaTravelTime_u_min * factor() * minutes(destination)}, so the time of
 * the search is valued like car travel time; the global {@code car.constantParkingSearchPenalty_min} stays 0 and this
 * zone column is the only source of parking search time.
 *
 * <p>Bound by {@link ParkingModule} with the tariff model and the factor of the module when the braunschweigParking
 * module is enabled, and by {@code BraunschweigModeChoiceModule} as {@link #none()} otherwise, so that the car utility
 * estimator needs no optional injection and a run without the module is unchanged: factor 0 and 0 minutes everywhere.
 *
 * <p>The search time applies to the destination whatever its purpose: the spec attaches it to the zone, not to the
 * stay, so a destination of purpose home that lies in a zone carries the zone's search minutes too (ASSUMPTION; the home
 * stay itself is never charged, rule H1, which concerns the fee only).
 *
 * <p>Immutable and thread-safe; no side effects.
 */
public final class ParkingSearchTime {
	private static final ParkingSearchTime NONE = new ParkingSearchTime(null, 0.0, null);

	/** The tariff model of the run; null for {@link #none()}, which has no zones. */
	private final ParkingTariffs tariffs;
	private final double factor;
	private final String tariffsPath;

	/**
	 * The search time of a run with the braunschweigParking module.
	 *
	 * @param tariffs the tariff model: its zones carry the search time in minutes
	 * @param parking the enabled module: its searchTimeFactor scales the minutes and its tariffsPath names the model in
	 *        error messages
	 */
	@Inject
	public ParkingSearchTime(ParkingTariffs tariffs, ParkingConfigGroup parking) {
		this(Objects.requireNonNull(tariffs, "tariffs"), parking.searchTimeFactor(), parking.getTariffsPath());
	}

	private ParkingSearchTime(ParkingTariffs tariffs, double factor, String tariffsPath) {
		this.tariffs = tariffs;
		this.factor = factor;
		this.tariffsPath = tariffsPath;
	}

	/** The search time of a run without the braunschweigParking module: factor 0 and no search minutes anywhere. */
	public static ParkingSearchTime none() {
		return NONE;
	}

	/**
	 * The factor that scales the search minutes into the car utility (dimensionless, in [0, 10]): 0 is off, version 1
	 * and the default; {@link #none()} has 0.
	 */
	public double factor() {
		return factor;
	}

	/**
	 * The parking search time in minutes of a destination: the {@code search_time_min} of the zone named by its activity
	 * attribute {@value ZoneParkingCarCostModel#ZONE_ATTRIBUTE}; 0 for a destination outside every zone (assumption Z1),
	 * for a zone whose column is empty ("0 where empty") and always for {@link #none()}. The result does not depend on
	 * the factor.
	 *
	 * @throws IllegalStateException for a zone attribute that is not a String or names a zone the tariff model does not
	 *         contain: the plans and the model must come from the same release, and {@link ParkingPopulationCheck} has
	 *         checked every plan at the controller start already
	 */
	public double minutes(Activity destination) {
		if (tariffs == null) {
			return 0.0;
		}
		Object value = destination.getAttributes().getAttribute(ZoneParkingCarCostModel.ZONE_ATTRIBUTE);
		if (value == null) {
			return 0.0;
		}
		if (!(value instanceof String zoneId)) {
			throw new IllegalStateException("activity " + destination.getType() + ": attribute "
					+ ZoneParkingCarCostModel.ZONE_ATTRIBUTE + " must be a java.lang.String, got " + value.getClass().getName()
					+ " " + value + "; the eqasim-bs plans writer writes it with this type");
		}
		ZoneTariff tariff = tariffs.zone(zoneId).orElseThrow(() -> new IllegalStateException("activity "
				+ destination.getType() + ": " + ZoneParkingCarCostModel.ZONE_ATTRIBUTE + " " + zoneId
				+ " is not a zone of the parking tariff model " + tariffsPath + " (" + ParkingConfigGroup.GROUP_NAME
				+ ".tariffsPath); the tariff model and the plans must come from the same release"));
		return tariff.searchTimeMinutes().orElse(0);
	}
}
