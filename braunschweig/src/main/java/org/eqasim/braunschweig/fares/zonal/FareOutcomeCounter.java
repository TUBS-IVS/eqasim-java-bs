package org.eqasim.braunschweig.fares.zonal;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import com.google.inject.Singleton;

/** Thread-safe per-iteration counts of fare quote outcomes (replanning quotes trips in parallel). */
@Singleton
public final class FareOutcomeCounter {
	private final ConcurrentHashMap<String, LongAdder> counts = new ConcurrentHashMap<>();

	public void record(String outcome) {
		counts.computeIfAbsent(outcome, key -> new LongAdder()).increment();
	}

	/** Sorted copy of the current counts; the counter is empty afterwards. */
	public Map<String, Long> snapshotAndReset() {
		Map<String, Long> snapshot = new TreeMap<>();
		for (Map.Entry<String, LongAdder> entry : counts.entrySet()) {
			long value = entry.getValue().sumThenReset();
			if (value > 0) {
				snapshot.put(entry.getKey(), value);
			}
		}
		counts.clear();
		return snapshot;
	}

	/** Share of fallback outcomes among all counted quote outcomes (informational labels excluded); 0 when empty. */
	public static double fallbackShare(Map<String, Long> snapshot) {
		long total = quoteTotal(snapshot);
		if (total == 0) {
			return 0.0;
		}
		long fallback = snapshot.entrySet().stream().filter(entry -> FareQuote.FALLBACK_OUTCOMES.contains(entry.getKey()))
				.mapToLong(Map.Entry::getValue).sum();
		return fallback / (double) total;
	}

	/** Number of counted quotes, i.e. all entries except informational labels. */
	public static long quoteTotal(Map<String, Long> snapshot) {
		return snapshot.entrySet().stream().filter(entry -> !FareQuote.INFORMATIONAL_LABELS.contains(entry.getKey()))
				.mapToLong(Map.Entry::getValue).sum();
	}
}
