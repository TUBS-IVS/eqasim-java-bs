package org.eqasim.braunschweig.parking;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import com.google.inject.Singleton;

/**
 * Thread-safe counts of the {@link ParkingOutcome}s the zone parking car cost model decided since the last snapshot
 * (design "parking cost zones", section 5.2, and the outcome CSV v2 of the v2 spec amendment A5; eqasim-bs issue #436).
 * Mode choice prices car alternatives in parallel replanning threads, and every priced car trip records one outcome with
 * the zone of its destination and the purpose of the stay, so the counts show which rule priced the car stays of an
 * iteration, where and for what (fallback transparency: an implausible NO_ZONE share exposes an empty zone join). Counts
 * are per cell {@link Cell} (outcome, zone id, purpose); they are aggregate counts, no persons. A second count holds the
 * priced stays whose priced interval the minimum parked duration extended (assumption L1), so the report shows how often
 * that assumption, rather than the simulated stay, set the priced duration.
 *
 * <p>A cell has its adder from its first record on, and adders are only ever reset, never removed; the same holds for the
 * extension count. An increment that runs concurrently with {@link #snapshotAndReset()} or
 * {@link #snapshotAndResetMinimumStayExtensions()} is therefore counted exactly once: in that snapshot or in the next.
 * The two snapshots are separate calls, so a car trip priced between them could fall into different iterations; the
 * report takes both at the end of an iteration, when replanning, which prices the car trips, has finished. The number of
 * cells is bounded by outcomes x zones x purposes of the run.
 */
@Singleton
public final class ParkingOutcomeCounter {
	/**
	 * One cell of the outcome report: how often the rule {@code outcome} priced a car stay in zone {@code zoneId} for
	 * purpose {@code purpose}. Cells order by outcome declaration order, then zone id, then purpose, the order of the
	 * rows of the report.
	 *
	 * @param outcome the rule that decided the price
	 * @param zoneId id of the parking zone of the stay, empty exactly for NO_ZONE (a stay outside every zone has none)
	 * @param purpose the MATSim activity type of the destination (home, work, education, shop, ...)
	 */
	public record Cell(ParkingOutcome outcome, String zoneId, String purpose) implements Comparable<Cell> {
		public Cell {
			Objects.requireNonNull(outcome, "outcome");
			Objects.requireNonNull(zoneId, "zoneId");
			Objects.requireNonNull(purpose, "purpose");
			if (outcome == ParkingOutcome.NO_ZONE && !zoneId.isEmpty()) {
				throw new IllegalArgumentException("a stay outside every zone (NO_ZONE) has no zone id, got '" + zoneId + "'");
			}
			if (outcome != ParkingOutcome.NO_ZONE && zoneId.isEmpty()) {
				throw new IllegalArgumentException("outcome " + outcome + " needs the id of the zone of the stay");
			}
		}

		@Override
		public int compareTo(Cell other) {
			int byOutcome = outcome.compareTo(other.outcome);
			if (byOutcome != 0) {
				return byOutcome;
			}
			int byZone = zoneId.compareTo(other.zoneId);
			return byZone != 0 ? byZone : purpose.compareTo(other.purpose);
		}
	}

	private final ConcurrentHashMap<Cell, LongAdder> counts = new ConcurrentHashMap<>();
	private final LongAdder minimumStayExtensions = new LongAdder();

	/**
	 * Counts one car stay priced by {@code outcome}.
	 *
	 * @param outcome the rule that decided the price
	 * @param zoneId id of the parking zone of the stay; null for NO_ZONE, the only outcome of a stay in no zone
	 * @param purpose the MATSim activity type of the destination
	 * @throws IllegalArgumentException for NO_ZONE with a zone id, and for any other outcome without one
	 */
	public void record(ParkingOutcome outcome, String zoneId, String purpose) {
		Cell cell = new Cell(outcome, zoneId == null ? "" : zoneId, purpose);
		counts.computeIfAbsent(cell, key -> new LongAdder()).increment();
	}

	/**
	 * The count of every cell with at least one stay since the previous snapshot, in the order of the report rows (outcome
	 * declaration order, then zone id, then purpose); unmodifiable. The counter restarts from 0.
	 */
	public SortedMap<Cell, Long> snapshotAndReset() {
		SortedMap<Cell, Long> snapshot = new TreeMap<>();
		for (Map.Entry<Cell, LongAdder> entry : counts.entrySet()) {
			long count = entry.getValue().sumThenReset();
			if (count > 0) {
				snapshot.put(entry.getKey(), count);
			}
		}
		return Collections.unmodifiableSortedMap(snapshot);
	}

	/**
	 * The counts of a snapshot summed per outcome, every outcome present in declaration order (0 for an outcome that did
	 * not occur); unmodifiable.
	 */
	public static Map<ParkingOutcome, Long> countsByOutcome(Map<Cell, Long> cells) {
		Map<ParkingOutcome, Long> counts = new EnumMap<>(ParkingOutcome.class);
		for (ParkingOutcome outcome : ParkingOutcome.values()) {
			counts.put(outcome, 0L);
		}
		for (Map.Entry<Cell, Long> entry : cells.entrySet()) {
			counts.merge(entry.getKey().outcome(), entry.getValue(), Long::sum);
		}
		return Collections.unmodifiableMap(counts);
	}

	/** Counts one priced car stay whose priced interval the minimum parked duration extended (assumption L1). */
	public void recordMinimumStayExtension() {
		minimumStayExtensions.increment();
	}

	/**
	 * The priced car stays the minimum parked duration extended since the previous call; the count restarts from 0.
	 * Independent of {@link #snapshotAndReset()}, which neither reads nor resets it.
	 */
	public long snapshotAndResetMinimumStayExtensions() {
		return minimumStayExtensions.sumThenReset();
	}
}
