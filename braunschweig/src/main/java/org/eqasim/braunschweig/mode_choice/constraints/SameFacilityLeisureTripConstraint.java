package org.eqasim.braunschweig.mode_choice.constraints;

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
 * the two activities has the type {@code leisure}, may only use one of {@link #ALLOWED_MODES} ({@code walk} or
 * {@code bicycle}). Every other trip is left to the other constraints. The rule needs no marker in the population.
 * It also rejects the initial (diary) mode of a caught trip when that mode is not allowed; {@code walk} is always
 * available ({@code BraunschweigModeAvailability}), so a caught trip always keeps a valid mode.
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
 * Inert unless a config selects {@link BraunschweigModeChoiceModule#SAME_FACILITY_LEISURE_TRIP_CONSTRAINT_NAME} among
 * the trip constraints of the discrete mode choice; the tour-based model applies it through the stock
 * {@code FromTripBased} tour constraint.
 */
public class SameFacilityLeisureTripConstraint extends AbstractTripConstraint {
	static final String LEISURE_ACTIVITY_TYPE = "leisure";

	/** The only modes a caught trip may use: the fork's names for walking and cycling. */
	public static final Set<String> ALLOWED_MODES = Set.of(TransportMode.walk, BraunschweigModeChoiceModule.BICYCLE);

	private final AtomicLong rejectedCandidates;

	SameFacilityLeisureTripConstraint(AtomicLong rejectedCandidates) {
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
		if (!appliesTo(trip) || ALLOWED_MODES.contains(mode)) {
			return true;
		}

		rejectedCandidates.incrementAndGet();
		return false;
	}

	/**
	 * Creates the constraint per replanned plan and counts the trips of the plan and those the rule applies to. Bound
	 * as a singleton so the counters are shared by all mode choice threads and the
	 * {@link SameFacilityLeisureTripSummaryListener}; the counters are thread-safe and count per constraint creation
	 * (one per replanned plan), not per distinct person or trip.
	 *
	 * <p>
	 * Rejected candidates count every rejection decision: the tour-based model checks a trip once per candidate mode
	 * chain, so this number grows with the chains evaluated, not with the trips.
	 *
	 * <p>
	 * Logging: a running line whenever the applied-trip count reaches (or passes) a power of two (the only output of
	 * the standalone mode choice, which fires no controller events) and a final line at the end of a MATSim run
	 * ({@link #logSummary()}).
	 */
	public static class Factory implements TripConstraintFactory {
		private static final Logger logger = LogManager.getLogger(SameFacilityLeisureTripConstraint.class);

		private final AtomicLong trips = new AtomicLong();
		private final AtomicLong appliedTrips = new AtomicLong();
		private final AtomicLong rejectedCandidates = new AtomicLong();

		@Override
		public TripConstraint createConstraint(Person person, List<DiscreteModeChoiceTrip> planTrips,
				Collection<String> availableModes) {
			int applied = 0;

			for (DiscreteModeChoiceTrip trip : planTrips) {
				if (appliesTo(trip)) {
					applied++;
				}
			}

			record(planTrips.size(), applied);
			return new SameFacilityLeisureTripConstraint(rejectedCandidates);
		}

		private void record(int planTrips, int applied) {
			// Every thread adds its trips before its applied trips (applied <= trips per call). Reading the trip total
			// after the applied update therefore covers every applied trip counted so far.
			trips.addAndGet(planTrips);

			if (applied == 0) {
				return;
			}

			long previousApplied = appliedTrips.getAndAdd(applied);
			long totalApplied = previousApplied + applied;

			// Running summary whenever the applied count reaches (or passes) a power of two.
			if (Long.highestOneBit(totalApplied) > previousApplied) {
				logger.info(summaryLine(totalApplied, trips.get(), rejectedCandidates.get()));
			}
		}

		/**
		 * Logs the totals so far as the final summary line (also when the rule applied to no trip). Logs nothing when
		 * the factory created no constraint, for example when the constraint is not selected. Called at the end of a
		 * MATSim run by {@link SameFacilityLeisureTripSummaryListener}.
		 */
		public void logSummary() {
			// Applied first, trips last: the trip total then covers every applied trip read (see record).
			long totalApplied = appliedTrips.get();
			long totalTrips = trips.get();

			if (totalTrips == 0) {
				return;
			}

			logger.info(summaryLine(totalApplied, totalTrips, rejectedCandidates.get()));
		}

		static String summaryLine(long applied, long trips, long rejected) {
			return String.format(Locale.ROOT,
					"same-facility leisure trips kept on foot or by bicycle: %d of %d trips (%.2f %%), "
							+ "%d candidate modes rejected",
					applied, trips, 100.0 * applied / trips, rejected);
		}

		/** Trips of all plans the factory created a constraint for. */
		public long getTrips() {
			return trips.get();
		}

		/** Of those, the trips the rule applies to. */
		public long getAppliedTrips() {
			return appliedTrips.get();
		}

		/** Candidate modes the rule rejected, over all created constraints. */
		public long getRejectedCandidates() {
			return rejectedCandidates.get();
		}
	}
}
