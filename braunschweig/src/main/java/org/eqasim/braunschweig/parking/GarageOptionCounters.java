package org.eqasim.braunschweig.parking;

import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe counts of the garage pricing, so that no fallback fires silently (CLAUDE.md "Fallback transparency"; spec
 * Amendment E of parking cost zones v2, eqasim-bs issue #436). The counters mirror the Python reference
 * {@code braunschweig.parking.cost.GarageOptionCounters}; mode choice prices car alternatives in parallel replanning
 * threads, so every count is a {@link LongAdder}. {@link ParkingOutcomeReportListener} takes one {@link #snapshotAndReset()}
 * per iteration and logs the rates.
 *
 * <p>Meaning of the counts, as in the Python reference: {@code stays} is every stay the garage calculator priced;
 * {@code eligible} the stays in a street or resident zone that pass the early rules while the garage options are on;
 * {@code withGaragesInRange} the eligible stays with at least one garage within the maximum distance; {@code streetFree}
 * those whose street option costs 0 and pay 0 (spec E4); {@code streetUnavailable} those priced over the garages alone
 * (spec E4); {@code expected} the stays priced as an expectation (outcome PAID_EXPECTED); {@code optionEvaluations} the
 * garage options priced; {@code closedScheduleRepeats} the garage options whose stay was longer than a closed duration
 * schedule and was priced by repeating it per started 24 h (ASSUMPTION P9).
 *
 * <p>A concurrent increment during {@link #snapshotAndReset()} is counted exactly once, in that snapshot or the next; the
 * fields of one snapshot are read one after the other, so the report takes it at the end of an iteration, when replanning
 * has finished.
 */
public final class GarageOptionCounters {
	/**
	 * The counts since the previous snapshot.
	 *
	 * @param stays every stay the garage calculator priced
	 * @param eligible stays in a street or resident zone that passed the early rules with the garage options on
	 * @param withGaragesInRange eligible stays with at least one garage within the maximum distance
	 * @param streetFree stays priced 0 because the street option costs 0 (E4)
	 * @param streetUnavailable stays priced over the garages alone because the street product is unavailable (E4)
	 * @param expected stays priced as an expectation (PAID_EXPECTED)
	 * @param optionEvaluations garage options priced
	 * @param closedScheduleRepeats garage options priced by repeating a closed schedule per started 24 h (P9)
	 */
	public record Snapshot(long stays, long eligible, long withGaragesInRange, long streetFree, long streetUnavailable,
			long expected, long optionEvaluations, long closedScheduleRepeats) {
		/** One log line with the counts and the rates (an empty denominator gives a plain count). */
		public String summary() {
			return "stays " + stays + "; eligible " + eligible + "; garages in range " + rate(withGaragesInRange, eligible)
					+ "; street free (E4) " + rate(streetFree, withGaragesInRange) + "; street unavailable "
					+ rate(streetUnavailable, withGaragesInRange) + "; priced as expectation " + expected
					+ "; garage options priced " + optionEvaluations + ", closed schedule repeated (P9) "
					+ rate(closedScheduleRepeats, optionEvaluations);
		}

		private static String rate(long part, long whole) {
			if (whole == 0) {
				return part + "/" + whole;
			}
			return String.format(java.util.Locale.ROOT, "%d/%d (%.1f %%)", part, whole, 100.0 * part / whole);
		}
	}

	private final LongAdder stays = new LongAdder();
	private final LongAdder eligible = new LongAdder();
	private final LongAdder withGaragesInRange = new LongAdder();
	private final LongAdder streetFree = new LongAdder();
	private final LongAdder streetUnavailable = new LongAdder();
	private final LongAdder expected = new LongAdder();
	private final LongAdder optionEvaluations = new LongAdder();
	private final LongAdder closedScheduleRepeats = new LongAdder();

	void countStay() {
		stays.increment();
	}

	void countEligible() {
		eligible.increment();
	}

	void countWithGaragesInRange() {
		withGaragesInRange.increment();
	}

	void countStreetFree() {
		streetFree.increment();
	}

	void countStreetUnavailable() {
		streetUnavailable.increment();
	}

	void countExpected() {
		expected.increment();
	}

	void countOptionEvaluation() {
		optionEvaluations.increment();
	}

	void countClosedScheduleRepeat() {
		closedScheduleRepeats.increment();
	}

	/** The current counts, without resetting them (tests and diagnostics; the report uses {@link #snapshotAndReset()}). */
	public Snapshot snapshot() {
		return new Snapshot(stays.sum(), eligible.sum(), withGaragesInRange.sum(), streetFree.sum(), streetUnavailable.sum(),
				expected.sum(), optionEvaluations.sum(), closedScheduleRepeats.sum());
	}

	/** The counts since the previous call; every count restarts from 0. */
	public Snapshot snapshotAndReset() {
		return new Snapshot(stays.sumThenReset(), eligible.sumThenReset(), withGaragesInRange.sumThenReset(),
				streetFree.sumThenReset(), streetUnavailable.sumThenReset(), expected.sumThenReset(),
				optionEvaluations.sumThenReset(), closedScheduleRepeats.sumThenReset());
	}
}
