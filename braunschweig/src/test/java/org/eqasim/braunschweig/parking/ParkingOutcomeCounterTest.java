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
import org.eqasim.braunschweig.parking.ParkingOutcomeCounter.PricedStay;
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
		counter.record(PricedStay.withoutGarage(ParkingOutcome.PAID_METERED, "fx_bs_ib", "work", 0.0));
		counter.record(PricedStay.withoutGarage(ParkingOutcome.NO_ZONE, null, "work", 0.0));
		counter.record(PricedStay.withoutGarage(ParkingOutcome.PAID_METERED, "fx_bs_ia", "shop", 0.0));
		counter.record(PricedStay.withoutGarage(ParkingOutcome.PAID_METERED, "fx_bs_ib", "work", 0.0));
		counter.record(PricedStay.withoutGarage(ParkingOutcome.PAID_METERED, "fx_bs_ia", "leisure", 0.0));
		counter.record(PricedStay.withoutGarage(ParkingOutcome.HOME, "fx_bs_ia", "home", 0.0));
		counter.record(PricedStay.withoutGarage(ParkingOutcome.PAID_GARAGE, "fx_wob_v2", "shop", 0.0));

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
		counter.record(PricedStay.withoutGarage(ParkingOutcome.HOME, "fx_bs_ia", "home", 0.0));
		assertEquals(Map.of(new Cell(ParkingOutcome.HOME, "fx_bs_ia", "home"), 1L), counter.snapshotAndReset());
	}

	/**
	 * The report lists outcomes in declaration order, so the order is part of the file format: the outcomes of version 1
	 * keep their positions; the two schema-2 outcomes, the schema-3 outcome PAID_EXPECTED and the two schema-5 outcomes
	 * NO_FEASIBLE_OPTION and EV_EXEMPT are appended at the end, in the order of the Python reference OUTCOMES.
	 */
	@Test
	public void theOutcomeDeclarationOrderKeepsVersionOneAndAppendsTheLaterOutcomes() {
		List<String> names = new ArrayList<>();
		for (ParkingOutcome outcome : ParkingOutcome.values()) {
			names.add(outcome.name());
		}
		assertEquals(List.of("NO_ZONE", "HOME", "EMPLOYER_FREE", "RESIDENT_FREE", "OUTSIDE_FEE_HOURS", "FREE_WITHIN_LIMIT",
				"PAID_METERED", "PAID_LONG_STAY", "PAID_CAMPUS_MEMBER", "PAID_CAMPUS_GUEST", "PAID_GARAGE", "PAID_COMMUTER",
				"PAID_EXPECTED", "NO_FEASIBLE_OPTION", "EV_EXEMPT"),
				names);
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
		assertThrows(NullPointerException.class, () -> counter.record(PricedStay.withoutGarage(null, "fx_bs_ia", "work", 0.0)));
		assertThrows(NullPointerException.class,
			() -> counter.record(PricedStay.withoutGarage(ParkingOutcome.NO_ZONE, null, null, 0.0)));
		assertThrows(IllegalArgumentException.class,
			() -> counter.record(PricedStay.withoutGarage(ParkingOutcome.NO_ZONE, "fx_bs_ia", "work", 0.0)));
		assertThrows(IllegalArgumentException.class,
			() -> counter.record(PricedStay.withoutGarage(ParkingOutcome.PAID_METERED, null, "work", 0.0)));
		assertThrows(IllegalArgumentException.class,
			() -> counter.record(PricedStay.withoutGarage(ParkingOutcome.PAID_METERED, "", "work", 0.0)));
		// A rejected record counts nothing.
		assertTrue(counter.snapshotAndReset().isEmpty());
	}

	/** The log line and the priced-stay share need the counts per outcome, every outcome listed in declaration order. */
	@Test
	public void countsByOutcomeSumsTheCellsOverZonesAndPurposes() {
		ParkingOutcomeCounter counter = new ParkingOutcomeCounter();
		counter.record(PricedStay.withoutGarage(ParkingOutcome.PAID_METERED, "fx_bs_ia", "shop", 0.0));
		counter.record(PricedStay.withoutGarage(ParkingOutcome.PAID_METERED, "fx_bs_ib", "work", 0.0));
		counter.record(PricedStay.withoutGarage(ParkingOutcome.PAID_METERED, "fx_bs_ib", "work", 0.0));
		counter.record(PricedStay.withoutGarage(ParkingOutcome.NO_ZONE, null, "leisure", 0.0));
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
		counter.record(PricedStay.withoutGarage(ParkingOutcome.PAID_METERED, "fx_bs_ia", "shop", 0.0));
		counter.recordMinimumStayExtension();
		counter.record(PricedStay.withoutGarage(ParkingOutcome.PAID_METERED, "fx_bs_ia", "shop", 0.0));
		counter.recordMinimumStayExtension();
		counter.record(PricedStay.withoutGarage(ParkingOutcome.PAID_METERED, "fx_bs_ia", "shop", 0.0));
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
						counter.record(PricedStay.withoutGarage(cell.outcome(), cell.zoneId().isEmpty() ? null : cell.zoneId(),
							cell.purpose(), 0.0));
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

	/**
	 * Spec Amendment E7: a cell sums the garage probability of its stays and counts its stays with a garage in range; a stay
	 * recorded without garage options counts a stay no garage acted on. Spec v3 A4: the sum is split into the paid and the
	 * free garage options, and the cell sums the expected cost, counts the infeasible streets and repeats the outcome counts
	 * of NO_FEASIBLE_OPTION and EV_EXEMPT. The next snapshot starts from zero.
	 */
	@Test
	public void aCellSumsTheGarageProbabilityAndCountsTheStaysWithGaragesInRange() {
		ParkingOutcomeCounter counter = new ParkingOutcomeCounter();
		counter.record(new PricedStay(ParkingOutcome.PAID_EXPECTED, "fx_a", "shop", 120.5, 0.25, 0.0, 1, false));
		counter.record(new PricedStay(ParkingOutcome.PAID_EXPECTED, "fx_a", "shop", 200.25, 0.25, 0.25, 3, true));
		counter.record(new PricedStay(ParkingOutcome.PAID_EXPECTED, "fx_a", "work", 80.0, 0.0, 1.0, 2, false));
		counter.record(new PricedStay(ParkingOutcome.FREE_WITHIN_LIMIT, "fx_a", "shop", 0.0, 0.0, 0.0, 2, false));
		counter.record(PricedStay.withoutGarage(ParkingOutcome.FREE_WITHIN_LIMIT, "fx_a", "shop", 0.0));
		counter.record(new PricedStay(ParkingOutcome.NO_FEASIBLE_OPTION, "fx_ia", "work", 720.0, 0.0, 0.0, 0, true));
		counter.record(new PricedStay(ParkingOutcome.NO_FEASIBLE_OPTION, "fx_ia", "work", 360.0, 0.0, 0.0, 0, true));
		counter.record(new PricedStay(ParkingOutcome.EV_EXEMPT, "fx_ev", "shop", 0.0, 0.0, 0.0, 1, false));

		Map<Cell, ParkingOutcomeCounter.CellTotals> totals = counter.snapshotAndResetTotals();

		assertEquals(new ParkingOutcomeCounter.CellTotals(2, 0.5, 0.25, 2, 1, 0, 320.75, 0),
				totals.get(new Cell(ParkingOutcome.PAID_EXPECTED, "fx_a", "shop")));
		assertEquals(0.75, totals.get(new Cell(ParkingOutcome.PAID_EXPECTED, "fx_a", "shop")).garageProbabilitySum(), 0.0);
		assertEquals(new ParkingOutcomeCounter.CellTotals(1, 0.0, 1.0, 1, 0, 0, 80.0, 0),
				totals.get(new Cell(ParkingOutcome.PAID_EXPECTED, "fx_a", "work")));
		assertEquals(new ParkingOutcomeCounter.CellTotals(2, 0.0, 0.0, 1, 0, 0, 0.0, 0),
				totals.get(new Cell(ParkingOutcome.FREE_WITHIN_LIMIT, "fx_a", "shop")));
		// The two outcome counts repeat the count of the row of their outcome and are 0 in every other row.
		assertEquals(new ParkingOutcomeCounter.CellTotals(2, 0.0, 0.0, 0, 2, 2, 1080.0, 0),
				totals.get(new Cell(ParkingOutcome.NO_FEASIBLE_OPTION, "fx_ia", "work")));
		assertEquals(new ParkingOutcomeCounter.CellTotals(1, 0.0, 0.0, 1, 0, 0, 0.0, 1),
				totals.get(new Cell(ParkingOutcome.EV_EXEMPT, "fx_ev", "shop")));
		assertTrue(counter.snapshotAndResetTotals().isEmpty());
		assertTrue(counter.snapshotAndReset().isEmpty());
	}

	/** The expected cost is summed in fixed point, so the sum does not depend on the order in which threads add their stays. */
	@Test
	public void theExpectedCostSumIsExactWhateverTheThreadOrder() throws Exception {
		ParkingOutcomeCounter counter = new ParkingOutcomeCounter();
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);
		try {
			List<Future<?>> recorders = new ArrayList<>();
			for (int thread = 0; thread < THREADS; thread++) {
				recorders.add(executor.submit(() -> {
					for (int index = 0; index < RECORDS_PER_THREAD; index++) {
						counter.record(PricedStay.withoutGarage(ParkingOutcome.PAID_METERED, "fx_a", "shop", 0.1));
					}
					return null;
				}));
			}
			for (Future<?> recorder : recorders) {
				recorder.get(60, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
		}
		long stays = (long) THREADS * RECORDS_PER_THREAD;
		ParkingOutcomeCounter.CellTotals cell = counter.snapshotAndResetTotals()
				.get(new Cell(ParkingOutcome.PAID_METERED, "fx_a", "shop"));
		assertEquals(stays, cell.count());
		assertEquals(0.1 * stays, cell.expectedCostCentsSum(), 1e-6);
	}

	@Test
	public void recordRejectsAnImplausibleExpectedCost() {
		ParkingOutcomeCounter counter = new ParkingOutcomeCounter();
		for (double cost : new double[] { -1.0, Double.NaN, Double.POSITIVE_INFINITY }) {
			assertThrows("cost " + cost, IllegalArgumentException.class,
					() -> counter.record(PricedStay.withoutGarage(ParkingOutcome.PAID_METERED, "fx_a", "shop", cost)));
		}
		assertTrue(counter.snapshotAndReset().isEmpty());
	}

	@Test
	public void recordRejectsAnImplausibleGarageProbability() {
		ParkingOutcomeCounter counter = new ParkingOutcomeCounter();
		assertThrows(IllegalArgumentException.class,
				() -> counter.record(new PricedStay(ParkingOutcome.PAID_EXPECTED, "fx_a", "shop", 0.0, 1.5, 0.0, 1, false)));
		assertThrows(IllegalArgumentException.class,
				() -> counter.record(new PricedStay(ParkingOutcome.PAID_EXPECTED, "fx_a", "shop", 0.0, -0.1, 0.0, 1, false)));
		assertThrows(IllegalArgumentException.class,
				() -> counter.record(new PricedStay(ParkingOutcome.PAID_EXPECTED, "fx_a", "shop", 0.0, Double.NaN, 0.0, 1, false)));
		assertThrows(IllegalArgumentException.class,
				() -> counter.record(new PricedStay(ParkingOutcome.PAID_EXPECTED, "fx_a", "shop", 0.0, 0.5, 0.0, -1, false)));
		// Nothing was recorded by a failing call.
		assertTrue(counter.snapshotAndReset().isEmpty());
	}

	/** The garage probability sum does not depend on the order in which threads add their stays (fixed-point sum). */
	@Test
	public void theGarageProbabilitySumIsExactWhateverTheThreadOrder() throws Exception {
		ParkingOutcomeCounter counter = new ParkingOutcomeCounter();
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);
		try {
			List<Future<?>> recorders = new ArrayList<>();
			for (int thread = 0; thread < THREADS; thread++) {
				recorders.add(executor.submit(() -> {
					for (int index = 0; index < RECORDS_PER_THREAD; index++) {
						counter.record(new PricedStay(ParkingOutcome.PAID_EXPECTED, "fx_a", "shop", 0.0, 0.1, 0.0, 1, false));
					}
					return null;
				}));
			}
			for (Future<?> recorder : recorders) {
				recorder.get(60, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
		}
		ParkingOutcomeCounter.CellTotals totals = counter.snapshotAndResetTotals()
				.get(new Cell(ParkingOutcome.PAID_EXPECTED, "fx_a", "shop"));
		assertEquals((long) THREADS * RECORDS_PER_THREAD, totals.count());
		assertEquals(0.1 * THREADS * RECORDS_PER_THREAD, totals.garageProbabilitySum(), 1e-6);
		assertEquals((long) THREADS * RECORDS_PER_THREAD, totals.staysWithGaragesInRange());
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
