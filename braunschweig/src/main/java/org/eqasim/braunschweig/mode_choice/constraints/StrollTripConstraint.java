package org.eqasim.braunschweig.mode_choice.constraints;

import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.braunschweig.mode_choice.BraunschweigModeChoiceModule;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.constraints.AbstractTripConstraint;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripConstraint;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripConstraintFactory;

/**
 * Keeps a trip into a stroll activity on foot or by bicycle (eqasim-bs#679).
 *
 * <p>
 * The population pipeline of eqasim-bs#679 marks the activity of an out-and-back stroll with the activity attribute
 * {@link #OUT_AND_BACK_STROLL_ATTRIBUTE} (a {@code java.lang.Boolean}). Rule: a trip whose destination activity carries the
 * attribute with the value {@link Boolean#TRUE} may only use one of
 * {@link SameFacilityLeisureTripConstraint#ALLOWED_MODES} (the same set as the same-facility leisure rule, not a
 * copy). Only a real {@code Boolean.TRUE} counts: a {@code String} "true", {@code Boolean.FALSE} or a missing
 * attribute place no restriction, and every other trip is left to the other constraints.
 *
 * <p>
 * ASSUMPTION (owner's decision of 2026-11-26 for eqasim-bs#679): a trip into a stroll is not a motorised trip.
 *
 * <p>
 * Inert unless a config selects {@link BraunschweigModeChoiceModule#STROLL_TRIP_CONSTRAINT_NAME} among the trip
 * constraints of the discrete mode choice. No coordination with {@link BorderTourVehicleConstraint} is needed: the
 * pipeline writes the attribute only on a stroll that starts at home and whose next trip ends at home, so its tour
 * (home, stroll, home) never touches an {@code outside} activity and is never a border tour.
 */
public class StrollTripConstraint extends AbstractTripConstraint {
	/** The destination activity attribute the pipeline writes (Boolean) for an out-and-back stroll activity. */
	public static final String OUT_AND_BACK_STROLL_ATTRIBUTE = "outAndBackStroll";

	private final AtomicLong reachedTrips;
	private final AtomicLong rejectedCandidates;

	/** Plan positions ({@link DiscreteModeChoiceTrip#getIndex()}) of the caught trips already counted as evaluated. */
	private final BitSet reached = new BitSet();

	StrollTripConstraint(AtomicLong reachedTrips, AtomicLong rejectedCandidates) {
		this.reachedTrips = reachedTrips;
		this.rejectedCandidates = rejectedCandidates;
	}

	/** Whether the rule applies to the trip: the destination activity attribute is the Boolean true. */
	public static boolean appliesTo(DiscreteModeChoiceTrip trip) {
		return Boolean.TRUE.equals(trip.getDestinationActivity().getAttributes().getAttribute(OUT_AND_BACK_STROLL_ATTRIBUTE));
	}

	@Override
	public boolean validateBeforeEstimation(DiscreteModeChoiceTrip trip, String mode, List<String> previousModes) {
		if (!appliesTo(trip)) {
			return true;
		}

		// The contrib's models create one constraint per chooseModes call (one plan, one thread): no lock needed.
		if (!reached.get(trip.getIndex())) {
			reached.set(trip.getIndex());
			reachedTrips.incrementAndGet();
		}

		if (SameFacilityLeisureTripConstraint.ALLOWED_MODES.contains(mode)) {
			return true;
		}

		rejectedCandidates.incrementAndGet();
		return false;
	}

	/**
	 * Creates the constraint per replanned plan. Bound as a singleton so the counters are shared by all mode choice
	 * threads and the {@link SameFacilityLeisureTripSummaryListener}. Counters as in
	 * {@link SameFacilityLeisureTripConstraint.Factory}: plan trips, caught trips, evaluated trips (a caught trip in a
	 * tour the mode choice does not re-choose is never asked about and keeps its initial mode: caught minus evaluated
	 * is the share the rule did not reach) and the candidate modes this rule rejected first (order-dependent).
	 * Logging: a running line when the number of created constraints reaches a power of two and a final line from
	 * {@link #logSummary()}.
	 */
	public static class Factory implements TripConstraintFactory {
		private static final Logger logger = LogManager.getLogger(StrollTripConstraint.class);

		private final AtomicLong plans = new AtomicLong();
		private final AtomicLong trips = new AtomicLong();
		private final AtomicLong caughtTrips = new AtomicLong();
		private final AtomicLong reachedTrips = new AtomicLong();
		private final AtomicLong rejectedCandidates = new AtomicLong();

		@Override
		public TripConstraint createConstraint(Person person, List<DiscreteModeChoiceTrip> planTrips,
				Collection<String> availableModes) {
			long previousPlans = plans.getAndIncrement();

			if (previousPlans > 0 && Long.bitCount(previousPlans) == 1) {
				logger.info(currentSummaryLine());
			}

			int caught = 0;

			for (DiscreteModeChoiceTrip trip : planTrips) {
				if (appliesTo(trip)) {
					caught++;
				}
			}

			// Plan trips before caught trips: a reader taking caught first and plan trips last sees caught <= trips.
			trips.addAndGet(planTrips.size());
			caughtTrips.addAndGet(caught);
			return new StrollTripConstraint(reachedTrips, rejectedCandidates);
		}

		/**
		 * Logs the totals so far as the final summary line (also when the rule caught no trip). Logs nothing when the
		 * factory created no constraint, for example when the constraint is not selected.
		 */
		public void logSummary() {
			if (plans.get() == 0) {
				return;
			}

			logger.info(currentSummaryLine());
		}

		private String currentSummaryLine() {
			// Evaluated first, caught next, plan trips last: each count read covers the ones read before it.
			long evaluated = reachedTrips.get();
			long rejected = rejectedCandidates.get();
			long caught = caughtTrips.get();
			long planTrips = trips.get();
			return summaryLine(caught, planTrips, evaluated, rejected);
		}

		static String summaryLine(long caught, long planTrips, long evaluated, long rejected) {
			long notEvaluated = caught - evaluated;
			return String.format(Locale.ROOT,
					"stroll trips: caught %d of %d plan trips; the mode choice evaluated %d of them "
							+ "(%.2f %%), %d (%.2f %%) lie in tours it does not re-choose and keep their mode; "
							+ "%d candidate modes this rule rejected first",
					caught, planTrips, evaluated, share(evaluated, caught), notEvaluated, share(notEvaluated, caught),
					rejected);
		}

		private static double share(long part, long whole) {
			return whole == 0 ? 0.0 : 100.0 * part / whole;
		}

		/** Trips of all plans the factory created a constraint for. */
		public long getTrips() {
			return trips.get();
		}

		/** Of those, the trips the rule applies to. */
		public long getCaughtTrips() {
			return caughtTrips.get();
		}

		/** Of those, the trips the mode choice asked this rule about at least once (per created constraint). */
		public long getReachedTrips() {
			return reachedTrips.get();
		}

		/** Candidate modes this rule rejected first (order-dependent). */
		public long getRejectedCandidates() {
			return rejectedCandidates.get();
		}
	}
}
