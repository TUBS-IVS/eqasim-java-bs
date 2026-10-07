package org.eqasim.braunschweig.parking;

import java.util.Objects;

import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;

import com.google.inject.Inject;

/**
 * Parking search time of a car trip destination (design "parking cost zones v2", lever 4; eqasim-bs issue #436): the
 * {@code search_time_min} column of the tariff model for the zone the destination lies in, and the factor
 * {@code braunschweigParking.searchTimeFactor} that scales it into the car utility. The utility term itself lives in
 * {@code BraunschweigCarUtilityEstimator}: {@code betaTravelTime_u_min * factor() * minutes(person, destination)}, so the time of
 * the search is valued like car travel time; the global {@code car.constantParkingSearchPenalty_min} stays 0 and this
 * zone column is the only source of parking search time.
 *
 * <p>Bound by {@link ParkingModule} with the tariff model and the factor of the module when the braunschweigParking
 * module is enabled, and by {@code BraunschweigModeChoiceModule} as {@link #none()} otherwise, so that the car utility
 * estimator needs no optional injection and a run without the module is unchanged: factor 0 and 0 minutes everywhere.
 *
 * <p>The search time applies to a destination of every purpose except home (ASSUMPTION, ruling R-T3-a): parking at home
 * is free (rule H1) and needs no search, so a destination of purpose home gets none, whatever its zone. The purpose test is
 * the one of the home rule of the fee ({@link ParkingCostCalculator#isHomePurpose}), and {@link #minutesIn} is the single
 * predicate of where the search time acts, used by the car utility term and by the coverage count of
 * {@link ParkingPopulationCheck}.
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
	 * The single predicate of where the parking search time acts: the search minutes of a destination of this purpose in this
	 * zone, i.e. the {@code search_time_min} of the zone (0 where the column is empty) for every purpose except home, and 0
	 * for the home purpose (rule H1: parking at home is free and needs no search). The car utility term
	 * ({@link #minutes}) and the coverage count of {@link ParkingPopulationCheck} both call it, so the count never includes a
	 * destination the term ignores.
	 *
	 * @param zone the zone the destination lies in
	 * @param purpose the MATSim activity type of the destination
	 */
	static int minutesIn(ZoneTariff zone, String purpose) {
		if (ParkingCostCalculator.isHomePurpose(purpose)) {
			return 0;
		}
		return zone.searchTimeMinutesOrZero();
	}

	/**
	 * The parking search time in minutes of a destination: the {@code search_time_min} of the zone named by its activity
	 * attribute {@value ZoneParkingCarCostModel#ZONE_ATTRIBUTE}; 0 for a destination outside every zone (assumption Z1),
	 * for a zone whose column is empty ("0 where empty"), for a destination of purpose home ({@link #minutesIn}) and
	 * always for {@link #none()}. The result does not depend on the factor.
	 *
	 * @param person the person who travels: only an error message uses it, to name the owner of the failing attribute
	 * @param destination the destination activity of the trip
	 * @throws IllegalStateException for a zone attribute that is not a String or names a zone the tariff model does not
	 *         contain, in the one format of {@link ParkingAttributes} (person, plan, activity, attribute, value, tariffs
	 *         path): the plans and the model must come from the same release, and {@link ParkingPopulationCheck} has
	 *         checked every plan at the controller start already
	 */
	public double minutes(Person person, Activity destination) {
		if (tariffs == null) {
			return 0.0;
		}
		ZoneTariff zone = ParkingAttributes.zone(tariffs, tariffsPath, person, destination);
		if (zone == null) {
			return 0.0;
		}
		return minutesIn(zone, destination.getType());
	}
}
