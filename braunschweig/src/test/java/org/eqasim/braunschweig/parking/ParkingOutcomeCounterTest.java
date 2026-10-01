package org.eqasim.braunschweig.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eqasim.braunschweig.parking.ParkingOutcomeCounter.Cell;
import org.junit.Test;

/**
 * Per-iteration parking outcome counts per cell (outcome, zone id, purpose). Mode choice prices car trips in parallel
 * replanning threads, so the counter must neither lose nor double count a stay, also when a snapshot is taken while
 * threads still record.
 */
public class ParkingOutcomeCounterTest {
	private static final int THREADS = 8;
	private static final int RECORDS_PER_THREAD = 20_000;

	/** Zone and purpose of the recorded stays: a stay outside every zone has no zone id, every other stay names its zone. */
	private static Cell cell(ParkingOutcome outcome, int variant) {
		String zoneId = outcome == ParkingOutcome.NO_ZONE ? "" : "fx_zone_" + (variant % 3);
		return new Cell(outcome, zoneId, variant % 2 == 0 ? "work" : "shop");
	}

	@Test
	public void snapshotHoldsEveryNonZeroCellInTheOrderOfTheReportAndResets() {
		ParkingOutcomeCounter counter = new ParkingOutcomeCounter();
		counter.record(ParkingOutcome.PAID_METERED, "fx_bs_ib", "work");
		counter.record(ParkingOutcome.NO_ZONE, null, "work");
		counter.record(ParkingOutcome.PAID_METERED, "fx_bs_ia", "shop");
		counter.record(ParkingOutcome.PAID_METERED, "fx_bs_ib", "work");
		counter.record(ParkingOutcome.PAID_METERED, "fx_bs_ia", "leisure");
		counter.record(ParkingOutcome.HOME, "fx_bs_ia", "home");
		counter.record(ParkingOutcome.PAID_GARAGE, "fx_wob_v2", "shop");

		Map<Cell, Long> snapshot = counter.snapshotAndReset();
		// Outcome declaration order, then zone id, then purpose; cells without a stay are not listed.
		Map<Cell, Long> expected = new TreeMap<>();
		expected.put(new Cell(ParkingOutcome.NO_ZONE, "", "work"), 1L);
		expected.put(new Cell(ParkingOutcome.HOME, "fx_bs_ia", "home"), 1L);
		expected.put(new Cell(ParkingOutcome.PAID_METERED, "fx_bs_ia", "leisure"), 1L);
		expected.put(new Cell(ParkingOutcome.PAID_METERED, "fx_bs_ia", "shop"), 1L);
		expected.put(new Cell(ParkingOutcome.PAID_METERED, "fx_bs_ib", "work"), 2L);
		expected.put(new Cell(ParkingOutcome.PAID_GARAGE, "fx_wob_v2", "shop"), 1L);
		assertEquals(expected, snapshot);
		assertEquals(List.copyOf(expected.keySet()), new ArrayList<>(snapshot.keySet()));
		assertThrows(UnsupportedOperationException.class,
				() -> snapshot.put(new Cell(ParkingOutcome.HOME, "fx_bs_ia", "work"), 1L));

		// The next snapshot starts from zero.
		assertTrue(counter.snapshotAndReset().isEmpty());
		counter.record(ParkingOutcome.HOME, "fx_bs_ia", "home");
		assertEquals(Map.of(new Cell(ParkingOutcome.HOME, "fx_bs_ia", "home"), 1L), counter.snapshotAndReset());
	}

	@Test
	public void cellsOrderByOutcomeDeclarationThenZoneIdThenPurpose() {
		List<Cell> inReportOrder = List.of(new Cell(ParkingOutcome.NO_ZONE, "", "shop"),
				new Cell(ParkingOutcome.NO_ZONE, "", "work"), new Cell(ParkingOutcome.PAID_METERED, "fx_a", "zzz"),
				new Cell(ParkingOutcome.PAID_METERED, "fx_b", "aaa"), new Cell(ParkingOutcome.PAID_COMMUTER, "fx_a", "aaa"));
		List<Cell> sorted = new ArrayList<>(inReportOrder);
		Collections.reverse(sorted);
		Collections.sort(sorted);
		assertEquals(inReportOrder, sorted);
	}

	/** A stay outside every zone has no zone id (the CSV cell is empty); every other outcome names the zone of its stay. */
	@Test
	public void recordRejectsAnInconsistentCellAndMissingValues() {
		ParkingOutcomeCounter counter = new ParkingOutcomeCounter();
		assertThrows(NullPointerException.class, () -> counter.record(null, "fx_bs_ia", "work"));
		assertThrows(NullPointerException.class, () -> counter.record(ParkingOutcome.NO_ZONE, null, null));
		assertThrows(IllegalArgumentException.class, () -> counter.record(ParkingOutcome.NO_ZONE, "fx_bs_ia", "work"));
		assertThrows(IllegalArgumentException.class, () -> counter.record(ParkingOutcome.PAID_METERED, null, "work"));
		assertThrows(IllegalArgumentException.class, () -> counter.record(ParkingOutcome.PAID_METERED, "", "work"));
		// A rejected record counts nothing.
		assertTrue(counter.snapshotAndReset().isEmpty());
	}

	/** The log line and the priced-stay share need the counts per outcome, every outcome listed in declaration order. */
	@Test
	public void countsByOutcomeSumsTheCellsOverZonesAndPurposes() {
		ParkingOutcomeCounter counter = new ParkingOutcomeCounter();
		counter.record(ParkingOutcome.PAID_METERED, "fx_bs_ia", "shop");
		counter.record(ParkingOutcome.PAID_METERED, "fx_bs_ib", "work");
		counter.record(ParkingOutcome.PAID_METERED, "fx_bs_ib", "work");
		counter.record(ParkingOutcome.NO_ZONE, null, "leisure");
		Map<ParkingOutcome, Long> counts = ParkingOutcomeCounter.countsByOutcome(counter.snapshotAndReset());
		assertEquals(List.of(ParkingOutcome.values()), new ArrayList<>(counts.keySet()));
		assertEquals(Long.valueOf(3), counts.get(ParkingOutcome.PAID_METERED));
		assertEquals(Long.valueOf(1), counts.get(ParkingOutcome.NO_ZONE));
		assertEquals(Long.valueOf(0), counts.get(ParkingOutcome.PAID_COMMUTER));
		assertEquals(4L, total(counts));
		assertThrows(UnsupportedOperationException.class, () -> counts.put(ParkingOutcome.HOME, 1L));
	}

	/**
	 * The priced stays the minimum parked duration extended (assumption L1) have their own count and snapshot: the
	 * outcome snapshot neither reads nor resets it.
	 */
	@Test
	public void minimumStayExtensionsAreCountedAndResetApartFromTheOutcomes() {
		ParkingOutcomeCounter counter = new ParkingOutcomeCounter();
		assertEquals(0L, counter.snapshotAndResetMinimumStayExtensions());
		counter.record(ParkingOutcome.PAID_METERED, "fx_bs_ia", "shop");
		counter.recordMinimumStayExtension();
		counter.record(ParkingOutcome.PAID_METERED, "fx_bs_ia", "shop");
		counter.recordMinimumStayExtension();
		counter.record(ParkingOutcome.PAID_METERED, "fx_bs_ia", "shop");
		assertEquals(Map.of(new Cell(ParkingOutcome.PAID_METERED, "fx_bs_ia", "shop"), 3L), counter.snapshotAndReset());
		assertEquals(2L, counter.snapshotAndResetMinimumStayExtensions());
		// The next snapshot starts from zero.
		assertEquals(0L, counter.snapshotAndResetMinimumStayExtensions());
	}

	@Test
	public void concurrentRecordsAreAllCounted() throws Exception {
		ParkingOutcomeCounter counter = new ParkingOutcomeCounter();
		runConcurrently(counter, () -> {
		});
		assertEquals(expectedCounts(), counter.snapshotAndReset());
	}

	/**
	 * Snapshots taken while the threads record partition the records between them: summed over all snapshots, every
	 * record is counted exactly once, whichever snapshot it fell into.
	 */
	@Test
	public void snapshotsDuringConcurrentRecordsNeitherLoseNorDoubleCount() throws Exception {
		ParkingOutcomeCounter counter = new ParkingOutcomeCounter();
		Map<Cell, Long> summed = new TreeMap<>();
		runConcurrently(counter, () -> add(summed, counter.snapshotAndReset()));
		add(summed, counter.snapshotAndReset());
		assertEquals(expectedCounts(), summed);
	}

	/** Thread t records the cell of outcome values()[(t + i) % n] with the variant (t + i) for i = 0 .. RECORDS - 1. */
	private static Map<Cell, Long> expectedCounts() {
		Map<Cell, Long> expected = new TreeMap<>();
		ParkingOutcome[] outcomes = ParkingOutcome.values();
		for (int thread = 0; thread < THREADS; thread++) {
			for (int index = 0; index < RECORDS_PER_THREAD; index++) {
				expected.merge(cell(outcomes[(thread + index) % outcomes.length], thread + index), 1L, Long::sum);
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
						Cell cell = cell(outcomes[(offset + index) % outcomes.length], offset + index);
						counter.record(cell.outcome(), cell.zoneId().isEmpty() ? null : cell.zoneId(), cell.purpose());
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

	private static void add(Map<Cell, Long> sum, Map<Cell, Long> snapshot) {
		for (Map.Entry<Cell, Long> entry : snapshot.entrySet()) {
			sum.merge(entry.getKey(), entry.getValue(), Long::sum);
		}
	}

	private static long total(Map<ParkingOutcome, Long> counts) {
		return counts.values().stream().mapToLong(Long::longValue).sum();
	}
}
