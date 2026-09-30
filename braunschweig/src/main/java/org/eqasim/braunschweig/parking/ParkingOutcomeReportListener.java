package org.eqasim.braunschweig.parking;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.StartupListener;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Reports the parking outcome counts of every iteration (design "parking cost zones", section 5.2; eqasim-bs issue
 * #436): writes {@code ITERS/it.N/N.parking_outcomes.csv} and logs one line
 * {@code [parking] it N: NO_ZONE a (x.x %), PAID_METERED b (y.y %), ...} with every outcome that occurred, in
 * declaration order, or {@code [parking] it N: no car stay priced} when none did, followed by the line
 * {@code [parking] it N: minimum stay L min extended e of p priced stays (z.z %)} of assumption L1. At the controller
 * start it logs the active minimum parked duration once.
 *
 * <p>CSV columns: {@code outcome} (a {@link ParkingOutcome} name), {@code count} (car stays the rule priced in the
 * iteration) and {@code share} (count divided by all counted stays of the iteration, a fraction in [0, 1] with six
 * decimals; 0 when no stay was priced). One row per outcome in declaration order, zero counts included, so every
 * iteration file has the same rows.
 *
 * <p>Minimum parked duration (assumption L1, {@code braunschweigParking.minimumStayMinutes} = L): e counts the priced
 * stays whose priced interval the minimum extended, because the simulated stay (for the last activity of a plan the
 * stay of the terminal-stay rule T1) was shorter than L minutes; p counts the priced stays, the counted stays in a
 * parking zone: every outcome but NO_ZONE, which the car cost model decides before any stay exists. The share is e / p
 * in percent, 0 when p is 0. An extended stay need not cost more: the extension can lie outside the fee window, and the
 * home, employer-free and resident rules price 0 whatever the duration. The CSV does not carry these counts.
 *
 * <p>The counts are the calls of the zone parking car cost model in the iteration, one per car alternative that mode
 * choice priced, chosen or not: numbers of pricing calls, not of distinct trips or persons. The eqasim car predictor
 * (CarPredictor, a CachedVariablePredictor) calls the cost model whenever it is asked for another trip than on its
 * previous call: it keeps the variables of that one trip only, keyed by the identity of the DiscreteModeChoiceTrip
 * object, not by the route. Before it, the trip estimate cache of the discrete mode choice contrib (CachedTripEstimator,
 * for the cachedModes, which include car in the eqasim and Braunschweig configs) answers a repeated estimate of the same
 * trip at the same departure second without a call. Replanning happens before the iteration ends, so the iteration-0
 * file, written before any replanning, has zero counts. Version 1 reports only: no threshold, no failure.
 */
@Singleton
public final class ParkingOutcomeReportListener implements StartupListener, IterationEndsListener {
	private static final Logger LOG = LogManager.getLogger(ParkingOutcomeReportListener.class);

	/** File name inside the iteration directory; MATSim prefixes it with the iteration number. */
	static final String FILE_NAME = "parking_outcomes.csv";

	private static final String HEADER = "outcome,count,share";

	private final ParkingOutcomeCounter counter;
	private final OutputDirectoryHierarchy output;
	private final int minimumStayMinutes;
	private final int minimumStay_s;

	@Inject
	public ParkingOutcomeReportListener(ParkingOutcomeCounter counter, OutputDirectoryHierarchy output,
			ParkingConfigGroup parking) {
		this.counter = counter;
		this.output = output;
		this.minimumStayMinutes = parking.minimumStayMinutes();
		this.minimumStay_s = parking.minimumStay_s();
	}

	/** Logs the active minimum parked duration of the run once (assumption L1). */
	@Override
	public void notifyStartup(StartupEvent event) {
		LOG.info(minimumStaySetting(minimumStayMinutes, minimumStay_s));
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		int iteration = event.getIteration();
		Map<ParkingOutcome, Long> counts = counter.snapshotAndReset();
		long extendedStays = counter.snapshotAndResetMinimumStayExtensions();
		Path path = Path.of(output.getIterationFilename(iteration, FILE_NAME));
		try {
			writeCsv(path, counts);
		} catch (IOException error) {
			throw new UncheckedIOException("cannot write the parking outcome report " + path, error);
		}
		LOG.info(summary(iteration, counts));
		LOG.info(minimumStaySummary(iteration, minimumStayMinutes, extendedStays, pricedStays(counts)));
	}

	/** The startup line of the minimum parked duration; a minimum of 0 is the version 1 pricing. */
	private static String minimumStaySetting(int minimumStayMinutes, int minimumStay_s) {
		String setting = "[parking] minimum stay " + minimumStayMinutes + " min (" + ParkingConfigGroup.GROUP_NAME
				+ ".minimumStayMinutes, assumption L1): ";
		if (minimumStayMinutes == 0) {
			return setting + "car stays are priced as simulated (version 1)";
		}
		return setting + "every priced car stay lasts at least " + minimumStay_s + " s; the simulated timing is unchanged";
	}

	/** The minimum parked duration line of one iteration: the priced stays it extended, as a count and in percent. */
	private static String minimumStaySummary(int iteration, int minimumStayMinutes, long extendedStays, long pricedStays) {
		return String.format(Locale.ROOT, "[parking] it %d: minimum stay %d min extended %d of %d priced stays (%.1f %%)",
				iteration, minimumStayMinutes, extendedStays, pricedStays, 100.0 * share(extendedStays, pricedStays));
	}

	/** Counted stays in a parking zone: every outcome but NO_ZONE, which the cost model decides before any stay exists. */
	private static long pricedStays(Map<ParkingOutcome, Long> counts) {
		return total(counts) - counts.getOrDefault(ParkingOutcome.NO_ZONE, 0L);
	}

	private static void writeCsv(Path path, Map<ParkingOutcome, Long> counts) throws IOException {
		long total = total(counts);
		StringBuilder csv = new StringBuilder(HEADER).append('\n');
		for (ParkingOutcome outcome : ParkingOutcome.values()) {
			long count = counts.getOrDefault(outcome, 0L);
			csv.append(outcome.name()).append(',').append(count).append(',')
					.append(String.format(Locale.ROOT, "%.6f", share(count, total))).append('\n');
		}
		if (path.getParent() != null) {
			Files.createDirectories(path.getParent());
		}
		Files.writeString(path, csv.toString(), StandardCharsets.UTF_8);
	}

	/** The {@code [parking]} log line of one iteration: every outcome with a non-zero count and its share in percent. */
	private static String summary(int iteration, Map<ParkingOutcome, Long> counts) {
		long total = total(counts);
		if (total == 0) {
			return "[parking] it " + iteration + ": no car stay priced";
		}
		List<String> parts = new ArrayList<>();
		for (ParkingOutcome outcome : ParkingOutcome.values()) {
			long count = counts.getOrDefault(outcome, 0L);
			if (count > 0) {
				parts.add(String.format(Locale.ROOT, "%s %d (%.1f %%)", outcome.name(), count, 100.0 * share(count, total)));
			}
		}
		return "[parking] it " + iteration + ": " + String.join(", ", parts);
	}

	private static double share(long count, long total) {
		return total == 0 ? 0.0 : count / (double) total;
	}

	private static long total(Map<ParkingOutcome, Long> counts) {
		return counts.values().stream().mapToLong(Long::longValue).sum();
	}
}
