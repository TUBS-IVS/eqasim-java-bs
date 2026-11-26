package org.eqasim.braunschweig.mode_choice.constraints;

import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.braunschweig.mode_choice.BraunschweigModeChoiceModule;
import org.eqasim.core.simulation.mode_choice.constraints.OutsideConstraint;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.constraints.AbstractTripConstraint;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripConstraint;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripConstraintFactory;
import org.matsim.facilities.ActivityFacility;

/**
 * Keeps same-facility leisure trips on foot or by bicycle (eqasim-bs#679).
 *
 * <p>
 * The population pipeline of eqasim-bs#679 places a stroll or dog walk at the facility of the activity it starts from,
 * so the trip into it, and the diary's way home right after it, start and end at one facility. Without this rule the
 * mode choice treats these trips like any other and can give them {@code car} or {@code car_passenger}.
 *
 * <p>
 * Rule: a trip whose origin and destination activity carry the same non-null facility id, and where at least one of
 * the two activities has the type {@link #LEISURE_ACTIVITY_TYPE}, may only use one of {@link #ALLOWED_MODES}. Every
 * other trip is left to the other constraints. The rule needs no marker in the population. It also rejects the initial
 * (diary) mode of a caught trip when that mode is not allowed. {@code BraunschweigModeAvailability} offers
 * {@code walk} to every person except the subpopulation {@code freight} (only {@code truck}, and no leisure
 * activities), so a caught trip of a person the rule can meet always keeps a valid mode.
 *
 * <p>
 * ASSUMPTION (owner's decision of 2026-11-26 for eqasim-bs#679): a leisure trip that starts and ends at one facility
 * has no displacement and is not a motorised trip.
 *
 * <p>
 * Not covered: same-facility trips without a leisure end (for example home to home car round trips) and trips whose
 * initial mode is {@code outside} (those belong to {@link OutsideConstraint}); for them this constraint returns
 * {@code true}.
 *
 * <p>
 * Interplay with {@link BorderTourVehicleConstraint}: that rule pins every vehicle leg of a border tour to its initial
 * mode, so a caught trip with initial mode {@code car} in a border tour would have no valid candidate once both rules
 * are selected. When this constraint is selected among the trip constraints, the border rule therefore does not pin
 * a trip that {@link #appliesTo} catches, but still refuses a vehicle mode on it other than its initial mode: in a
 * border tour a caught {@code car} trip ends up on {@code walk}, a caught {@code bicycle} trip keeps the bicycle or
 * walks (see there).
 *
 * <p>
 * Inert unless a config selects {@link BraunschweigModeChoiceModule#SAME_FACILITY_LEISURE_TRIP_CONSTRAINT_NAME} among
 * the trip constraints of the discrete mode choice; the tour-based model applies it through the stock
 * {@code FromTripBased} tour constraint.
 */
public class SameFacilityLeisureTripConstraint extends AbstractTripConstraint {
	/**
	 * ASSUMPTION (the rule's definition, eqasim-bs ADR-0207, not a configuration value): the activity type that marks a
	 * leisure end. Fixed together with {@link #ALLOWED_MODES} because both define which trips the rule covers and what
	 * it permits; another type or mode set is another rule.
	 */
	public static final String LEISURE_ACTIVITY_TYPE = "leisure";

	/**
	 * ASSUMPTION (the rule's definition, eqasim-bs ADR-0207, not a configuration value): the only modes a caught trip
	 * may use, the fork's names for walking and cycling. Fixed together with {@link #LEISURE_ACTIVITY_TYPE}.
	 */
	public static final Set<String> ALLOWED_MODES = Set.of(TransportMode.walk, BraunschweigModeChoiceModule.BICYCLE);

	private final AtomicLong reachedTrips;
	private final AtomicLong rejectedCandidates;

	/** Plan positions ({@link DiscreteModeChoiceTrip#getIndex()}) of the caught trips already counted as evaluated. */
	private final BitSet reached = new BitSet();

	SameFacilityLeisureTripConstraint(AtomicLong reachedTrips, AtomicLong rejectedCandidates) {
		this.reachedTrips = reachedTrips;
		this.rejectedCandidates = rejectedCandidates;
	}

	/** Whether the rule applies to the trip: same non-null facility at both ends, a leisure end, not outside. */
	public static boolean appliesTo(DiscreteModeChoiceTrip trip) {
		if (OutsideConstraint.OUTSIDE_MODE.equals(trip.getInitialMode())) {
			return false;
		}

		Id<ActivityFacility> originFacilityId = trip.getOriginActivity().getFacilityId();

		if (originFacilityId == null || !originFacilityId.equals(trip.getDestinationActivity().getFacilityId())) {
			return false;
		}

		return LEISURE_ACTIVITY_TYPE.equals(trip.getOriginActivity().getType())
				|| LEISURE_ACTIVITY_TYPE.equals(trip.getDestinationActivity().getType());
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

		if (ALLOWED_MODES.contains(mode)) {
			return true;
		}

		rejectedCandidates.incrementAndGet();
		return false;
	}

	/**
	 * Creates the constraint per replanned plan. Bound as a singleton so the counters are shared by all mode choice
	 * threads and the {@link SameFacilityLeisureTripSummaryListener}; they are thread-safe and count per constraint
	 * creation (one per replanned plan), not per distinct person or trip.
	 *
	 * <p>
	 * Counters:
	 * <ul>
	 * <li>plan trips: all trips of the plans the factory created a constraint for;</li>
	 * <li>caught: of those, the trips the rule applies to;</li>
	 * <li>evaluated: of those, the trips the mode choice asked this rule about at least once. A caught trip in a tour
	 * the mode choice does not re-choose (dropped by a tour filter, for example the outside or the tour length filter)
	 * is never asked about and keeps its initial mode, motorised or not: caught minus evaluated is the share the rule
	 * did not reach (the fallback rate of this rule);</li>
	 * <li>rejected: the candidate modes this rule rejected first. The composite constraints stop at the first rejecting
	 * constraint, so the count depends on the order of the selected constraints ({@code RunAdaptConfig} rebuilds the
	 * trip constraint list from a {@code HashSet}) and includes candidates that a constraint asked later would reject
	 * too; the tour-based model asks once per candidate mode chain, so it grows with the chains, not with the
	 * trips.</li>
	 * </ul>
	 *
	 * <p>
	 * Logging: a running line whenever the number of created constraints reaches a power of two, with the totals of
	 * the plans created before (the only output of the standalone mode choice, which fires no controller events; plans
	 * other threads are still choosing may show as not yet evaluated), and a final line at the end of a MATSim run
	 * ({@link #logSummary()}).
	 */
	public static class Factory implements TripConstraintFactory {
		private static final Logger logger = LogManager.getLogger(SameFacilityLeisureTripConstraint.class);

		private final AtomicLong plans = new AtomicLong();
		private final AtomicLong trips = new AtomicLong();
		private final AtomicLong caughtTrips = new AtomicLong();
		private final AtomicLong reachedTrips = new AtomicLong();
		private final AtomicLong rejectedCandidates = new AtomicLong();

		@Override
		public TripConstraint createConstraint(Person person, List<DiscreteModeChoiceTrip> planTrips,
				Collection<String> availableModes) {
			long previousPlans = plans.getAndIncrement();

			// Running summary of the plans created before this one, after 1, 2, 4, ... earlier plans.
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
			return new SameFacilityLeisureTripConstraint(reachedTrips, rejectedCandidates);
		}

		/**
		 * Logs the totals so far as the final summary line (also when the rule caught no trip). Logs nothing when the
		 * factory created no constraint, for example when the constraint is not selected. Called at the end of a MATSim
		 * run by {@link SameFacilityLeisureTripSummaryListener}.
		 */
		public void logSummary() {
			if (plans.get() == 0) {
				return;
			}

			logger.info(currentSummaryLine());
		}

		private String currentSummaryLine() {
			// Evaluated first, caught next, plan trips last: each count read covers the ones read before it (a trip is
			// evaluated only after its plan's caught trips were added).
			long evaluated = reachedTrips.get();
			long rejected = rejectedCandidates.get();
			long caught = caughtTrips.get();
			long planTrips = trips.get();
			return summaryLine(caught, planTrips, evaluated, rejected);
		}

		static String summaryLine(long caught, long planTrips, long evaluated, long rejected) {
			long notEvaluated = caught - evaluated;
			return String.format(Locale.ROOT,
					"same-facility leisure trips: caught %d of %d plan trips; the mode choice evaluated %d of them "
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

		/** Candidate modes this rule rejected first (order-dependent, see the Javadoc of the factory). */
		public long getRejectedCandidates() {
			return rejectedCandidates.get();
		}
	}
}
