package org.eqasim.braunschweig.parking;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import com.google.inject.Singleton;

/**
 * Thread-safe counts of the {@link ParkingOutcome}s the zone parking car cost model decided since the last snapshot
 * (design "parking cost zones", section 5.2; eqasim-bs issue #436). Mode choice prices car alternatives in parallel
 * replanning threads, and every priced car trip records one outcome, so the counts show which rule priced the car stays
 * of an iteration (fallback transparency: an implausible NO_ZONE share exposes an empty zone join). A second count holds
 * the priced stays whose priced interval the minimum parked duration extended (assumption L1), so the report shows how
 * often that assumption, rather than the simulated stay, set the priced duration.
 *
 * <p>Every outcome has its adder from construction on, and adders are only ever reset, never removed; the same holds
 * for the extension count. An increment that runs concurrently with {@link #snapshotAndReset()} or
 * {@link #snapshotAndResetMinimumStayExtensions()} is therefore counted exactly once: in that snapshot or in the next.
 * The two snapshots are separate calls, so a car trip priced between them could fall into different iterations; the
 * report takes both at the end of an iteration, when replanning, which prices the car trips, has finished.
 */
@Singleton
public final class ParkingOutcomeCounter {
	private final ConcurrentHashMap<ParkingOutcome, LongAdder> counts = new ConcurrentHashMap<>();
	private final LongAdder minimumStayExtensions = new LongAdder();

	public ParkingOutcomeCounter() {
		for (ParkingOutcome outcome : ParkingOutcome.values()) {
			counts.put(outcome, new LongAdder());
		}
	}

	/** Counts one car stay priced by {@code outcome}. */
	public void record(ParkingOutcome outcome) {
		counts.get(Objects.requireNonNull(outcome, "outcome")).increment();
	}

	/**
	 * The count of every outcome since the previous snapshot, 0 for an outcome that did not occur, in declaration order;
	 * unmodifiable. The counter restarts from 0.
	 */
	public Map<ParkingOutcome, Long> snapshotAndReset() {
		Map<ParkingOutcome, Long> snapshot = new EnumMap<>(ParkingOutcome.class);
		for (ParkingOutcome outcome : ParkingOutcome.values()) {
			snapshot.put(outcome, counts.get(outcome).sumThenReset());
		}
		return Collections.unmodifiableMap(snapshot);
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
