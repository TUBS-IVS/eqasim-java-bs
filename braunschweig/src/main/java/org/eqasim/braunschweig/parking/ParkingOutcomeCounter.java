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
 * (design "parking cost zones", section 5.2, the outcome CSV v2 of the v2 spec amendment A5 and the outcome CSV v4 of spec
 * v3 A4; eqasim-bs issue #436). Mode choice prices car alternatives in parallel replanning threads, and every priced car
 * trip records one outcome with the zone of its destination and the purpose of the stay, so the counts show which rule priced
 * the car stays of an iteration, where and for what (fallback transparency: an implausible NO_ZONE share exposes an empty
 * zone join). Counts are per cell {@link Cell} (outcome, zone id, purpose); they are aggregate counts, no persons. A second
 * count holds the priced stays whose priced interval the minimum parked duration extended (assumption L1), so the report
 * shows how often that assumption, rather than the simulated stay, set the priced duration.
 *
 * <p>A cell has its adders from its first record on, and adders are only ever reset, never removed; the same holds for the
 * extension count. An increment that runs concurrently with {@link #snapshotAndReset()} or
 * {@link #snapshotAndResetMinimumStayExtensions()} is therefore counted exactly once: in that snapshot or in the next.
 * The two snapshots are separate calls, so a car trip priced between them could fall into different iterations; the
 * report takes both at the end of an iteration, when replanning, which prices the car trips, has finished. The number of
 * cells is bounded by outcomes x zones x purposes of the run.
 *
 * <p>Garage options (spec Amendment E7 of parking cost zones v2, spec v3 A4): every cell also sums the garage probability of
 * its stays, split into the probability of the garage options that cost more than 0 ct for the stay (paid) and of those that
 * cost 0 ct (free), and counts its stays with at least one garage in range, its stays whose street side was no option
 * (the maximum stay removed the street, spec v3 A1) and the sum of the expected parking cost of its stays in cents (the
 * unrounded expectation where the stay was priced as an expectation, else the priced cents; unscaled by the cost factor), so
 * that the expected garage share, the paid-only share and the mean expected cost per zone and purpose can be read from every
 * run. The sums are held in fixed point (1e-9 per unit for the probabilities, 1e-6 ct for the cost), rounded per stay, so
 * that they do not depend on the order in which parallel replanning threads add their stays. The garage option rates of the
 * whole run ({@link GarageOptionCounters}) are held here too, so that the cost model and the report share one singleton.
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
	 * One priced car stay as the cost model reports it to the counter.
	 *
	 * @param outcome the rule that decided the price
	 * @param zoneId id of the parking zone of the stay; null for NO_ZONE, the only outcome of a stay in no zone
	 * @param purpose the MATSim activity type of the destination
	 * @param expectedCostCents the expected parking cost of the stay in euro cents, at least 0, UNSCALED by the cost factor: the
	 *        unrounded expectation for a stay priced as an expectation, else the priced cents
	 * @param garageProbabilityPaid sum of the probabilities of the garage options that cost more than 0 ct for the stay, in
	 *        [0, 1]; 0 where no garage acted
	 * @param garageProbabilityFree sum of the probabilities of the garage options that cost 0 ct for the stay, in [0, 1]; 0
	 *        where no garage acted; paid plus free is at most 1
	 * @param garagesInRange garage options of the stay within the maximum distance, at least 0
	 * @param streetInfeasible the street side of the stay was no option (spec v3 A1)
	 */
	public record PricedStay(ParkingOutcome outcome, String zoneId, String purpose, double expectedCostCents,
			double garageProbabilityPaid, double garageProbabilityFree, int garagesInRange, boolean streetInfeasible) {
		public PricedStay {
			Objects.requireNonNull(outcome, "outcome");
			Objects.requireNonNull(purpose, "purpose");
			// The upper tolerance 1e-12 exists because the calculator sums the probabilities P_i = w_i / sum(w) in double
			// precision: the sum of up to a few dozen options can exceed 1 by rounding (about 1e-16 each). Anything beyond that
			// is a defect and fails; a negative part is never tolerated, which is why the calculator accumulates the paid and
			// the free part directly instead of subtracting one from the total.
			if (!(garageProbabilityPaid >= 0.0 && garageProbabilityFree >= 0.0
					&& garageProbabilityPaid + garageProbabilityFree <= 1.0 + 1e-12) || garagesInRange < 0) {
				throw new IllegalArgumentException("the paid and the free garage probability must lie in [0, 1] together and the"
						+ " garages in range must not be negative, got " + garageProbabilityPaid + ", " + garageProbabilityFree
						+ " and " + garagesInRange);
			}
			if (!(expectedCostCents >= 0.0) || Double.isInfinite(expectedCostCents)) {
				throw new IllegalArgumentException("the expected parking cost must be a finite number of euro cents >= 0, got "
						+ expectedCostCents);
			}
		}

		/** A stay that no garage acted on, whose street side was an option, priced {@code costCents}. */
		public static PricedStay withoutGarage(ParkingOutcome outcome, String zoneId, String purpose, double costCents) {
			return new PricedStay(outcome, zoneId, purpose, costCents, 0.0, 0.0, 0, false);
		}
	}

	/**
	 * What one cell of the report holds since the previous snapshot (CSV v4 columns count, the garage probability sums,
	 * stays_with_garages_in_range, street_infeasible_count, no_feasible_option_count, expected_cost_cents_sum and
	 * ev_exempt_count).
	 *
	 * @param count car stays the rule priced in the zone for the purpose
	 * @param garageProbabilitySumPaid sum over those stays of the probability of the garage options that cost more than 0 ct
	 * @param garageProbabilitySumFree sum over those stays of the probability of the garage options that cost 0 ct
	 * @param staysWithGaragesInRange stays of the cell that had at least one garage within the maximum distance, whether or
	 *        not the garage acted (a free street, spec E4, gives them probability 0)
	 * @param streetInfeasibleCount stays of the cell whose street side was no option (spec v3 A1)
	 * @param noFeasibleOptionCount stays of the cell priced NO_FEASIBLE_OPTION: the count of a NO_FEASIBLE_OPTION cell, else 0
	 * @param expectedCostCentsSum sum over those stays of the expected parking cost in cents, unscaled by the cost factor
	 * @param evExemptCount stays of the cell priced EV_EXEMPT: the count of an EV_EXEMPT cell, else 0
	 */
	public record CellTotals(long count, double garageProbabilitySumPaid, double garageProbabilitySumFree,
			long staysWithGaragesInRange, long streetInfeasibleCount, long noFeasibleOptionCount,
			double expectedCostCentsSum, long evExemptCount) {
		/** The sum over the stays of the probability that the stay uses one of the garages: paid plus free. */
		public double garageProbabilitySum() {
			return garageProbabilitySumPaid + garageProbabilitySumFree;
		}
	}

	/** The live counts of one cell; adders are only ever reset, never removed. */
	private static final class CellCounts {
		private final LongAdder count = new LongAdder();
		private final LongAdder paidProbabilityNanos = new LongAdder();
		private final LongAdder freeProbabilityNanos = new LongAdder();
		private final LongAdder staysWithGaragesInRange = new LongAdder();
		private final LongAdder streetInfeasible = new LongAdder();
		private final LongAdder expectedCostMicroCents = new LongAdder();
	}

	/** A garage probability is summed in units of 1e-9, rounded per stay, so that the sum is independent of thread order. */
	private static final double NANOS_PER_UNIT = 1e9;

	/**
	 * The expected cost is summed in units of 1e-6 ct (the sum of a cell holds up to 1e12 ct before it would overflow a long),
	 * rounded per stay, for the same order independence.
	 */
	private static final double MICRO_CENTS_PER_CENT = 1e6;

	private final ConcurrentHashMap<Cell, CellCounts> counts = new ConcurrentHashMap<>();
	private final LongAdder minimumStayExtensions = new LongAdder();
	private final GarageOptionCounters garageOptionCounters = new GarageOptionCounters();

	/**
	 * Counts one priced car stay (spec Amendment E7, spec v3 A4).
	 *
	 * @throws IllegalArgumentException for NO_ZONE with a zone id, and for any other outcome without one
	 */
	public void record(PricedStay stay) {
		Cell cell = new Cell(stay.outcome(), stay.zoneId() == null ? "" : stay.zoneId(), stay.purpose());
		CellCounts cellCounts = counts.computeIfAbsent(cell, key -> new CellCounts());
		cellCounts.count.increment();
		if (stay.garageProbabilityPaid() > 0.0) {
			cellCounts.paidProbabilityNanos.add(Math.round(stay.garageProbabilityPaid() * NANOS_PER_UNIT));
		}
		if (stay.garageProbabilityFree() > 0.0) {
			cellCounts.freeProbabilityNanos.add(Math.round(stay.garageProbabilityFree() * NANOS_PER_UNIT));
		}
		if (stay.garagesInRange() > 0) {
			cellCounts.staysWithGaragesInRange.increment();
		}
		if (stay.streetInfeasible()) {
			cellCounts.streetInfeasible.increment();
		}
		cellCounts.expectedCostMicroCents.add(Math.round(stay.expectedCostCents() * MICRO_CENTS_PER_CENT));
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
	 * The totals of every cell with at least one stay since the previous snapshot (the stays, their garage probabilities, their
	 * garages in range, their infeasible streets and their expected cost), in the order of the report rows; unmodifiable. The
	 * counter restarts from 0.
	 */
	public SortedMap<Cell, CellTotals> snapshotAndResetTotals() {
		SortedMap<Cell, CellTotals> snapshot = new TreeMap<>();
		for (Map.Entry<Cell, CellCounts> entry : counts.entrySet()) {
			CellCounts cellCounts = entry.getValue();
			long count = cellCounts.count.sumThenReset();
			long paidNanos = cellCounts.paidProbabilityNanos.sumThenReset();
			long freeNanos = cellCounts.freeProbabilityNanos.sumThenReset();
			long inRange = cellCounts.staysWithGaragesInRange.sumThenReset();
			long streetInfeasible = cellCounts.streetInfeasible.sumThenReset();
			long costMicroCents = cellCounts.expectedCostMicroCents.sumThenReset();
			if (count > 0) {
				ParkingOutcome outcome = entry.getKey().outcome();
				snapshot.put(entry.getKey(),
						new CellTotals(count, paidNanos / NANOS_PER_UNIT, freeNanos / NANOS_PER_UNIT, inRange, streetInfeasible,
								outcome == ParkingOutcome.NO_FEASIBLE_OPTION ? count : 0L, costMicroCents / MICRO_CENTS_PER_CENT,
								outcome == ParkingOutcome.EV_EXEMPT ? count : 0L));
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
