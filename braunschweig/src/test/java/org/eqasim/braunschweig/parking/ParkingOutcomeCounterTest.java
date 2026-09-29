package org.eqasim.braunschweig.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * Per-iteration parking outcome counts. Mode choice prices car trips in parallel replanning threads, so the counter must
 * neither lose nor double count an outcome, also when a snapshot is taken while threads still record.
 */
public class ParkingOutcomeCounterTest {
	private static final int THREADS = 8;
	private static final int RECORDS_PER_THREAD = 20_000;

	@Test
	public void snapshotHoldsEveryOutcomeInDeclarationOrderAndResets() {
		ParkingOutcomeCounter counter = new ParkingOutcomeCounter();
		counter.record(ParkingOutcome.PAID_METERED);
		counter.record(ParkingOutcome.NO_ZONE);
		counter.record(ParkingOutcome.PAID_METERED);

		Map<ParkingOutcome, Long> snapshot = counter.snapshotAndReset();
		assertEquals(List.of(ParkingOutcome.values()), new ArrayList<>(snapshot.keySet()));
		assertEquals(Long.valueOf(1), snapshot.get(ParkingOutcome.NO_ZONE));
		assertEquals(Long.valueOf(2), snapshot.get(ParkingOutcome.PAID_METERED));
		assertEquals(Long.valueOf(0), snapshot.get(ParkingOutcome.HOME));
		assertThrows(UnsupportedOperationException.class, () -> snapshot.put(ParkingOutcome.HOME, 1L));

		// The next snapshot starts from zero.
		Map<ParkingOutcome, Long> empty = counter.snapshotAndReset();
		assertEquals(ParkingOutcome.values().length, empty.size());
		assertEquals(0L, total(empty));
		counter.record(ParkingOutcome.HOME);
		assertEquals(Long.valueOf(1), counter.snapshotAndReset().get(ParkingOutcome.HOME));
	}

	@Test
	public void recordRejectsAMissingOutcome() {
		assertThrows(NullPointerException.class, () -> new ParkingOutcomeCounter().record(null));
	}

	@Test
	public void concurrentRecordsAreAllCounted() throws Exception {
		ParkingOutcomeCounter counter = new ParkingOutcomeCounter();
		runConcurrently(counter, () -> {
		});
		Map<ParkingOutcome, Long> snapshot = counter.snapshotAndReset();
		assertEquals(expectedCounts(), snapshot);
	}

	/**
	 * Snapshots taken while the threads record partition the records between them: summed over all snapshots, every
	 * record is counted exactly once, whichever snapshot it fell into.
	 */
	@Test
	public void snapshotsDuringConcurrentRecordsNeitherLoseNorDoubleCount() throws Exception {
		ParkingOutcomeCounter counter = new ParkingOutcomeCounter();
		Map<ParkingOutcome, Long> summed = new EnumMap<>(ParkingOutcome.class);
		runConcurrently(counter, () -> add(summed, counter.snapshotAndReset()));
		add(summed, counter.snapshotAndReset());
		assertEquals(expectedCounts(), summed);
	}

	/** Thread t records outcome values()[(t + i) % 10] for i = 0 .. RECORDS_PER_THREAD - 1. */
	private static Map<ParkingOutcome, Long> expectedCounts() {
		Map<ParkingOutcome, Long> expected = new EnumMap<>(ParkingOutcome.class);
		ParkingOutcome[] outcomes = ParkingOutcome.values();
		for (ParkingOutcome outcome : outcomes) {
			expected.put(outcome, 0L);
		}
		for (int thread = 0; thread < THREADS; thread++) {
			for (int index = 0; index < RECORDS_PER_THREAD; index++) {
				expected.merge(outcomes[(thread + index) % outcomes.length], 1L, Long::sum);
			}
		}
		return expected;
	}

	/** Starts all recording threads at once and runs {@code whileRecording} on this thread until they are done. */
	private static void runConcurrently(ParkingOutcomeCounter counter, Runnable whileRecording) throws Exception {
		ParkingOutcome[] outcomes = ParkingOutcome.values();
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<?>> recorders = new ArrayList<>();
			for (int thread = 0; thread < THREADS; thread++) {
				int offset = thread;
				recorders.add(executor.submit(() -> {
					start.await();
					for (int index = 0; index < RECORDS_PER_THREAD; index++) {
						counter.record(outcomes[(offset + index) % outcomes.length]);
					}
					return null;
				}));
			}
			start.countDown();
			while (!recorders.stream().allMatch(Future::isDone)) {
				whileRecording.run();
				Thread.onSpinWait();
			}
			for (Future<?> recorder : recorders) {
				// Propagates a failure inside a recording thread instead of hiding it.
				recorder.get(60, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
		}
	}

	private static void add(Map<ParkingOutcome, Long> sum, Map<ParkingOutcome, Long> snapshot) {
		for (Map.Entry<ParkingOutcome, Long> entry : snapshot.entrySet()) {
			sum.merge(entry.getKey(), entry.getValue(), Long::sum);
		}
	}

	private static long total(Map<ParkingOutcome, Long> counts) {
		return counts.values().stream().mapToLong(Long::longValue).sum();
	}
}
