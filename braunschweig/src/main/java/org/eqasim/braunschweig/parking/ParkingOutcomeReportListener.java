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
import org.eqasim.braunschweig.parking.ParkingOutcomeCounter.Cell;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.StartupListener;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Reports the parking outcome counts of every iteration (design "parking cost zones", section 5.2, and the outcome CSV v2
 * of the v2 spec amendment A5; eqasim-bs issue #436): writes {@code ITERS/it.N/N.parking_outcomes.csv} and logs one line
 * {@code [parking] it N: NO_ZONE a (x.x %), PAID_METERED b (y.y %), ...} with every outcome that occurred, in
 * declaration order, or {@code [parking] it N: no car stay priced} when none did, followed by the line
 * {@code [parking] it N: minimum stay L min extended e of p priced stays (z.z %)} of assumption L1. At the controller
 * start it logs the active minimum parked duration and the active search time factor once.
 *
 * <p>CSV v2 columns: {@code outcome} (a {@link ParkingOutcome} name), {@code zone_id} (the parking zone of the stays, empty
 * for NO_ZONE, the stays outside every zone), {@code purpose} (the activity type of the destination), {@code count} (car
 * stays the rule priced in that zone for that purpose in the iteration) and {@code share} (count divided by all counted
 * stays of the iteration, a fraction in [0, 1] with six decimals). One row per cell (outcome, zone, purpose) with a
 * non-zero count, in outcome declaration order, then zone id, then purpose, so a file lists exactly the cells that
 * occurred; an iteration without a priced stay has the header only. The rows are aggregate counts, no persons. A value
 * with a comma, a quote or a line break is written in quotes, as RFC 4180 has it. The file name is unchanged; version 1
 * of the report had the columns {@code outcome,count,share} and one row per outcome, zero counts included; readers of
 * version 1 (the smoke harness of the first zone parking run) read that format only.
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
 * file, written before any replanning, has no row. Version 1 reports only: no threshold, no failure.
 */
@Singleton
public final class ParkingOutcomeReportListener implements StartupListener, IterationEndsListener {
	private static final Logger LOG = LogManager.getLogger(ParkingOutcomeReportListener.class);

	/** File name inside the iteration directory; MATSim prefixes it with the iteration number. */
	static final String FILE_NAME = "parking_outcomes.csv";

	/** The columns of the report, CSV v2 (spec amendment A5). */
	static final String HEADER = "outcome,zone_id,purpose,count,share";

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
		Map<Cell, Long> cells = counter.snapshotAndReset();
		long extendedStays = counter.snapshotAndResetMinimumStayExtensions();
		Path path = Path.of(output.getIterationFilename(iteration, FILE_NAME));
		try {
			writeCsv(path, cells);
		} catch (IOException error) {
			throw new UncheckedIOException("cannot write the parking outcome report " + path, error);
		}
		Map<ParkingOutcome, Long> counts = ParkingOutcomeCounter.countsByOutcome(cells);
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

	private static void writeCsv(Path path, Map<Cell, Long> cells) throws IOException {
		long total = cells.values().stream().mapToLong(Long::longValue).sum();
		StringBuilder csv = new StringBuilder(HEADER).append('\n');
		for (Map.Entry<Cell, Long> entry : cells.entrySet()) {
			Cell cell = entry.getKey();
			long count = entry.getValue();
			csv.append(cell.outcome().name()).append(',').append(csvField(cell.zoneId())).append(',')
					.append(csvField(cell.purpose())).append(',').append(count).append(',')
					.append(String.format(Locale.ROOT, "%.6f", share(count, total))).append('\n');
		}
		if (path.getParent() != null) {
			Files.createDirectories(path.getParent());
		}
		Files.writeString(path, csv.toString(), StandardCharsets.UTF_8);
	}

	/** A text field of the CSV: quoted, with doubled quotes, when it contains a comma, a quote or a line break. */
	static String csvField(String value) {
		if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
			return value;
		}
		return '"' + value.replace("\"", "\"\"") + '"';
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
