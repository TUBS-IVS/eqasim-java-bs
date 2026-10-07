package org.eqasim.braunschweig.parking;

import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.core.simulation.mode_choice.parameters.ModeParameters;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.StartupListener;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Startup check of lever 4 (the parking search time of the zone-based parking costs; eqasim-bs issue #436) against the
 * tariff model and the mode parameters: the parking search time of the zone-based model must never be silently inert and
 * never be counted twice. A run with a factor above 0 that equals the baseline looks like a sensitivity arm but measures
 * nothing, and a second source of the same search time makes an arm measure something else than it says, so a setup defect
 * stops the run (fallback transparency: a path that cannot act must not look active). Bound by ParkingModule as a controller
 * listener, so it exists only when the braunschweigParking module is enabled; the check runs when the controller starts,
 * before iteration 0, and fails with an IllegalStateException when
 * <ul>
 * <li>the factor is above 0 and no zone of the tariff model carries a {@code search_time_min} above 0: the term would add 0
 * to every car utility. The message names the factor, the tariffs path and the reason; fill the column of the tariff table
 * for the zones it applies to, or set the factor to 0;</li>
 * <li>the factor is above 0 and the global {@value #CAR_PENALTY_PARAMETER} is not 0 (double counting): the car predictor adds
 * that penalty to the travel time of every car trip, and the term of lever 4 adds the {@code search_time_min} of the zone,
 * valued like car travel time too, so a trip into a zone with a search time would pay its parking search twice. The spec
 * makes the zone column the only source of parking search time; the message names both values and both ways out.</li>
 * </ul>
 * and warns when the global penalty is not 0 while the factor is 0: the zone column is then not a second source, but the
 * penalty still adds a parking search time that the zone-based model does not know, in every zone and outside them.
 *
 * <p>The penalty is read from the {@link ModeParameters} that BraunschweigModeChoiceModule binds: the singleton the car
 * predictor reads, after the mode parameters file and the {@code --mode-choice-parameter} command-line options have been
 * applied, so the check sees the value the utilities use. It is read at the startup event, not when the module is
 * configured, because the overrides are applied by the provider of that singleton, which Guice runs after the configuration
 * of every module.
 *
 * <p>With the default factor 0 (off) and the default penalty 0 the check never fails and never warns. Whether an activity of
 * the population lies in a zone with a search time is checked, as a warning, by {@link ParkingPopulationCheck}, which knows
 * the plans.
 *
 * <p>Side effects: the warning log line and the exception; the tariff model and the parameters are only read.
 */
@Singleton
public final class ParkingSearchTimeCheck implements StartupListener {
	private static final Logger LOG = LogManager.getLogger(ParkingSearchTimeCheck.class);

	/** The name of the global car parking search penalty in the mode parameters, as the command line and the file spell it. */
	static final String CAR_PENALTY_PARAMETER = "car.constantParkingSearchPenalty_min";

	private final ParkingTariffs tariffs;
	private final ModeParameters modeParameters;
	private final String tariffsPath;
	private final double searchTimeFactor;
	private final String searchTimeFactorText;

	/**
	 * @param tariffs the tariff model of the run: its zones carry the search time column
	 * @param parking the enabled module: its searchTimeFactor is the configured factor and its tariffsPath names the model in
	 *        the message
	 * @param modeParameters the mode parameters of the run, with every override applied: the car parking search penalty is
	 *        read from it at startup
	 */
	@Inject
	public ParkingSearchTimeCheck(ParkingTariffs tariffs, ParkingConfigGroup parking, ModeParameters modeParameters) {
		this.tariffs = Objects.requireNonNull(tariffs, "tariffs");
		this.modeParameters = Objects.requireNonNull(modeParameters, "modeParameters");
		this.tariffsPath = parking.getTariffsPath();
		this.searchTimeFactor = parking.searchTimeFactor();
		this.searchTimeFactorText = parking.getSearchTimeFactor();
	}

	/** Fails when lever 4 is configured but cannot act or would count twice, and warns about a penalty; see the class comment. */
	@Override
	public void notifyStartup(StartupEvent event) {
		if (searchTimeFactor > 0.0 && tariffs.zonesWithSearchTime() == 0) {
			throw new IllegalStateException(ParkingConfigGroup.GROUP_NAME + ".searchTimeFactor is " + searchTimeFactorText
					+ " (above 0), but no zone of the parking tariff model " + tariffsPath + " ("
					+ ParkingConfigGroup.GROUP_NAME + ".tariffsPath) carries a search_time_min above 0, so the parking search"
					+ " time cannot act and every car utility would equal the one without it; fill the search_time_min column of"
					+ " the tariff table for the zones it applies to, or set searchTimeFactor to 0");
		}
		double penalty_min = modeParameters.car.constantParkingSearchPenalty_min;
		// Any value but 0 is a parking search time of its own, a negative or an invalid (NaN) one included; -0.0 equals 0.0.
		if (penalty_min == 0.0) {
			return;
		}
		if (searchTimeFactor > 0.0) {
			throw new IllegalStateException(CAR_PENALTY_PARAMETER + " is " + penalty_min + " (not 0) and "
					+ ParkingConfigGroup.GROUP_NAME + ".searchTimeFactor is " + searchTimeFactorText + " (above 0): the parking"
					+ " search time would be counted twice, once as the global penalty that the car predictor adds to the travel"
					+ " time of every car trip and once as the search_time_min of the zone times the factor in the car utility;"
					+ " the search_time_min column of the parking tariff model " + tariffsPath + " is the only source of the"
					+ " parking search time of the zone-based parking costs, so set " + CAR_PENALTY_PARAMETER + " to 0 (mode"
					+ " parameters file or --mode-choice-parameter:" + CAR_PENALTY_PARAMETER + "=0), or set searchTimeFactor to 0");
		}
		LOG.warn("[parking] " + CAR_PENALTY_PARAMETER + " is " + penalty_min + " (not 0) while the "
				+ ParkingConfigGroup.GROUP_NAME + " module is enabled: the car predictor adds it to the travel time of every car"
				+ " trip, in the parking zones and outside them, on top of the zone-based parking costs; the spec makes the"
				+ " search_time_min column of the parking tariff model " + tariffsPath + " (lever 4, searchTimeFactor "
				+ searchTimeFactorText + ") the only source of parking search time, so unless this penalty is intended set "
				+ CAR_PENALTY_PARAMETER + " to 0 (mode parameters file or --mode-choice-parameter:" + CAR_PENALTY_PARAMETER
				+ "=0)");
	}
}
