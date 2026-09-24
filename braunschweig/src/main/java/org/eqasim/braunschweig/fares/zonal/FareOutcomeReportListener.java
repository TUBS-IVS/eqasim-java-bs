package org.eqasim.braunschweig.fares.zonal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Writes the fare outcome counts of every iteration to {@code ITERS/it.N/N.vrb_fare_outcomes.csv}
 * (columns iteration, outcome, count, share) and fails the run after the iteration when the fallback
 * share of the evaluated PT quotes exceeds vrbFare.maximumUnsupportedShare (ADR-0133 D8). Counts cover
 * every PT alternative mode choice evaluated in the iteration, not only the selected trips.
 */
@Singleton
public final class FareOutcomeReportListener implements IterationEndsListener {
	private static final Logger LOG = LogManager.getLogger(FareOutcomeReportListener.class);
	static final String FILE_NAME = "vrb_fare_outcomes.csv";

	private final FareOutcomeCounter counter;
	private final OutputDirectoryHierarchy output;
	private final VrbFareConfigGroup config;

	@Inject
	public FareOutcomeReportListener(FareOutcomeCounter counter, OutputDirectoryHierarchy output, VrbFareConfigGroup config) {
		this.counter = counter;
		this.output = output;
		this.config = config;
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		Path path = Path.of(output.getIterationFilename(event.getIteration(), FILE_NAME));
		try {
			writeReport(counter, path, event.getIteration(), config.maximumUnsupportedShare());
		} catch (IOException error) {
			throw new IllegalStateException("cannot write " + path, error);
		}
	}

	/** Snapshot, write the CSV, log the shares; throws AFTER writing when the fallback share exceeds the limit. */
	static Path writeReport(FareOutcomeCounter counter, Path path, int iteration, double maximumUnsupportedShare)
			throws IOException {
		Map<String, Long> snapshot = counter.snapshotAndReset();
		long quotes = FareOutcomeCounter.quoteTotal(snapshot);
		StringBuilder csv = new StringBuilder("iteration,outcome,count,share\n");
		for (Map.Entry<String, Long> entry : snapshot.entrySet()) {
			csv.append(iteration).append(',').append(entry.getKey()).append(',').append(entry.getValue()).append(',')
					.append(quotes == 0 ? "0" : Double.toString(entry.getValue() / (double) quotes)).append('\n');
		}
		if (path.getParent() != null) {
			Files.createDirectories(path.getParent());
		}
		Files.writeString(path, csv.toString(), StandardCharsets.UTF_8);
		double share = FareOutcomeCounter.fallbackShare(snapshot);
		LOG.info(String.format(Locale.ROOT, "[vrb-fares] iteration=%d quotes=%d fallback_share=%.4f outcomes=%s",
				iteration, quotes, share, snapshot));
		if (share > maximumUnsupportedShare) {
			throw new IllegalStateException(String.format(Locale.ROOT,
					"[vrb-fares] fallback share %.4f exceeds %.4f in iteration %d; see %s", share, maximumUnsupportedShare,
					iteration, path));
		}
		return path;
	}
}
