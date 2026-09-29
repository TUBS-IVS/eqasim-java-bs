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
import org.matsim.core.controler.listener.IterationEndsListener;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Reports the parking outcome counts of every iteration (design "parking cost zones", section 5.2; eqasim-bs issue
 * #436): writes {@code ITERS/it.N/N.parking_outcomes.csv} and logs one line
 * {@code [parking] it N: NO_ZONE a (x.x %), PAID_METERED b (y.y %), ...} with every outcome that occurred, in
 * declaration order, or {@code [parking] it N: no car stay priced} when none did.
 *
 * <p>CSV columns: {@code outcome} (a {@link ParkingOutcome} name), {@code count} (car stays the rule priced in the
 * iteration) and {@code share} (count divided by all counted stays of the iteration, a fraction in [0, 1] with six
 * decimals; 0 when no stay was priced). One row per outcome in declaration order, zero counts included, so every
 * iteration file has the same rows.
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
public final class ParkingOutcomeReportListener implements IterationEndsListener {
	private static final Logger LOG = LogManager.getLogger(ParkingOutcomeReportListener.class);

	/** File name inside the iteration directory; MATSim prefixes it with the iteration number. */
	static final String FILE_NAME = "parking_outcomes.csv";

	private static final String HEADER = "outcome,count,share";

	private final ParkingOutcomeCounter counter;
	private final OutputDirectoryHierarchy output;

	@Inject
	public ParkingOutcomeReportListener(ParkingOutcomeCounter counter, OutputDirectoryHierarchy output) {
		this.counter = counter;
		this.output = output;
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		int iteration = event.getIteration();
		Map<ParkingOutcome, Long> counts = counter.snapshotAndReset();
		Path path = Path.of(output.getIterationFilename(iteration, FILE_NAME));
		try {
			writeCsv(path, counts);
		} catch (IOException error) {
			throw new UncheckedIOException("cannot write the parking outcome report " + path, error);
		}
		LOG.info(summary(iteration, counts));
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
