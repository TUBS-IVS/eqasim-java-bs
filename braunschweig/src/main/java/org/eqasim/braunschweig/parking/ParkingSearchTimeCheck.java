package org.eqasim.braunschweig.parking;

import java.util.Objects;

import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.StartupListener;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Startup check of lever 4 (the parking search time of the zone-based parking costs; eqasim-bs issue #436) against the
 * tariff model: a configured {@code braunschweigParking.searchTimeFactor} must never be silently inert. A run with a factor
 * above 0 that equals the baseline looks like a sensitivity arm but measures nothing, so the setup defect stops the run
 * (fallback transparency: a path that cannot act must not look active). Bound by ParkingModule as a controller listener, so
 * it exists only when the braunschweigParking module is enabled; the check runs when the controller starts, before iteration
 * 0, and fails with an IllegalStateException when
 * <ul>
 * <li>the factor is above 0 and no zone of the tariff model carries a {@code search_time_min} above 0: the term would add 0
 * to every car utility. The message names the factor, the tariffs path and the reason; fill the column of the tariff table
 * for the zones it applies to, or set the factor to 0.</li>
 * </ul>
 * With the default factor 0 (off) the check never fails and never warns about the search time. Whether an activity of the
 * population lies in a zone with a search time is checked, as a warning, by {@link ParkingPopulationCheck}, which knows the
 * plans.
 *
 * <p>Side effects: none besides the exception; the tariff model is only read.
 */
@Singleton
public final class ParkingSearchTimeCheck implements StartupListener {
	private final ParkingTariffs tariffs;
	private final String tariffsPath;
	private final double searchTimeFactor;
	private final String searchTimeFactorText;

	/**
	 * @param tariffs the tariff model of the run: its zones carry the search time column
	 * @param parking the enabled module: its searchTimeFactor is the configured factor and its tariffsPath names the model in
	 *        the message
	 */
	@Inject
	public ParkingSearchTimeCheck(ParkingTariffs tariffs, ParkingConfigGroup parking) {
		this.tariffs = Objects.requireNonNull(tariffs, "tariffs");
		this.tariffsPath = parking.getTariffsPath();
		this.searchTimeFactor = parking.searchTimeFactor();
		this.searchTimeFactorText = parking.getSearchTimeFactor();
	}

	/** Fails when lever 4 is configured but cannot act; see the class comment. */
	@Override
	public void notifyStartup(StartupEvent event) {
		if (searchTimeFactor > 0.0 && tariffs.zonesWithSearchTime() == 0) {
			throw new IllegalStateException(ParkingConfigGroup.GROUP_NAME + ".searchTimeFactor is " + searchTimeFactorText
					+ " (above 0), but no zone of the parking tariff model " + tariffsPath + " ("
					+ ParkingConfigGroup.GROUP_NAME + ".tariffsPath) carries a search_time_min above 0, so the parking search"
					+ " time cannot act and every car utility would equal the one without it; fill the search_time_min column of"
					+ " the tariff table for the zones it applies to, or set searchTimeFactor to 0");
		}
	}
}
