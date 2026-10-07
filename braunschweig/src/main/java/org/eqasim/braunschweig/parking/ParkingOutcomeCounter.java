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
 *
 * <p>Garage options (spec Amendment E7 of parking cost zones v2): every cell also sums the garage probability of its stays
 * (the sum of the probabilities that the stay uses one of the garages within the maximum distance, 0 where no garage
 * acted) and counts its stays with at least one garage in range, so that the expected garage share per zone and purpose and
 * the calibration universe can be read from every run. The sum is held in fixed point (1e-9 per unit), so that it does not
 * depend on the order in which parallel replanning threads add their stays. The garage option rates of the whole run
 * ({@link GarageOptionCounters}) are held here too, so that the cost model and the report share one singleton.
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

	/**
	 * What one cell of the report holds since the previous snapshot (CSV v3 columns count, garage_probability_sum and
	 * stays_with_garages_in_range).
	 *
	 * @param count car stays the rule priced in the zone for the purpose
	 * @param garageProbabilitySum sum over those stays of the garage probability (spec E3), 0 for a stay no garage acted on
	 * @param staysWithGaragesInRange stays of the cell that had at least one garage within the maximum distance, whether or
	 *        not the garage acted (a free street, spec E4, gives them probability 0)
	 */
	public record CellTotals(long count, double garageProbabilitySum, long staysWithGaragesInRange) {
	}

	/** The live counts of one cell; adders are only ever reset, never removed. */
	private static final class CellCounts {
		private final LongAdder count = new LongAdder();
		private final LongAdder garageProbabilityNanos = new LongAdder();
		private final LongAdder staysWithGaragesInRange = new LongAdder();
	}

	/** A garage probability is summed in units of 1e-9, rounded per stay, so that the sum is independent of thread order. */
	private static final double NANOS_PER_UNIT = 1e9;

	private final ConcurrentHashMap<Cell, CellCounts> counts = new ConcurrentHashMap<>();
	private final LongAdder minimumStayExtensions = new LongAdder();
	private final GarageOptionCounters garageOptionCounters = new GarageOptionCounters();

	/**
	 * Counts one car stay priced by {@code outcome} that no garage acted on.
	 *
	 * @param outcome the rule that decided the price
	 * @param zoneId id of the parking zone of the stay; null for NO_ZONE, the only outcome of a stay in no zone
	 * @param purpose the MATSim activity type of the destination
	 * @throws IllegalArgumentException for NO_ZONE with a zone id, and for any other outcome without one
	 */
	public void record(ParkingOutcome outcome, String zoneId, String purpose) {
		record(outcome, zoneId, purpose, 0.0, 0);
	}

	/**
	 * Counts one car stay priced by {@code outcome} together with its garage options (spec Amendment E7).
	 *
	 * @param outcome the rule that decided the price
	 * @param zoneId id of the parking zone of the stay; null for NO_ZONE, the only outcome of a stay in no zone
	 * @param purpose the MATSim activity type of the destination
	 * @param garageProbability the sum of the garage probabilities of the stay, in [0, 1]; 0 where no garage acted
	 * @param garagesInRange garage options of the stay within the maximum distance, at least 0
	 * @throws IllegalArgumentException for NO_ZONE with a zone id, for any other outcome without one, a garage probability
	 *         outside [0, 1] and a negative number of garages
	 */
	public void record(ParkingOutcome outcome, String zoneId, String purpose, double garageProbability, int garagesInRange) {
		if (!(garageProbability >= 0.0 && garageProbability <= 1.0 + 1e-12) || garagesInRange < 0) {
			throw new IllegalArgumentException("a garage probability must lie in [0, 1] and the garages in range must not be"
					+ " negative, got " + garageProbability + " and " + garagesInRange);
		}
		Cell cell = new Cell(outcome, zoneId == null ? "" : zoneId, purpose);
		CellCounts cellCounts = counts.computeIfAbsent(cell, key -> new CellCounts());
		cellCounts.count.increment();
		if (garageProbability > 0.0) {
			cellCounts.garageProbabilityNanos.add(Math.round(garageProbability * NANOS_PER_UNIT));
		}
		if (garagesInRange > 0) {
			cellCounts.staysWithGaragesInRange.increment();
		}
	}

	/**
	 * The count of every cell with at least one stay since the previous snapshot, in the order of the report rows (outcome
	 * declaration order, then zone id, then purpose); unmodifiable. The counter restarts from 0.
	 */
	public SortedMap<Cell, Long> snapshotAndReset() {
		SortedMap<Cell, Long> snapshot = new TreeMap<>();
		snapshotAndResetTotals().forEach((cell, totals) -> snapshot.put(cell, totals.count()));
		return Collections.unmodifiableSortedMap(snapshot);
	}

	/**
	 * The totals of every cell with at least one stay since the previous snapshot (the stays, their garage probabilities and
	 * their garages in range), in the order of the report rows; unmodifiable. The counter restarts from 0.
	 */
	public SortedMap<Cell, CellTotals> snapshotAndResetTotals() {
		SortedMap<Cell, CellTotals> snapshot = new TreeMap<>();
		for (Map.Entry<Cell, CellCounts> entry : counts.entrySet()) {
			CellCounts cellCounts = entry.getValue();
			long count = cellCounts.count.sumThenReset();
			long nanos = cellCounts.garageProbabilityNanos.sumThenReset();
			long inRange = cellCounts.staysWithGaragesInRange.sumThenReset();
			if (count > 0) {
				snapshot.put(entry.getKey(), new CellTotals(count, nanos / NANOS_PER_UNIT, inRange));
			}
		}
		return Collections.unmodifiableSortedMap(snapshot);
	}

	/** The rates of the garage options of the whole run; the cost model counts into it, the report snapshots it. */
	public GarageOptionCounters garageOptionCounters() {
		return garageOptionCounters;
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
