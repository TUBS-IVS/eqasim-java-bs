package org.eqasim.core.scenario.routing;

import java.util.concurrent.atomic.AtomicLong;

import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;

/**
 * Moves a routed plan earlier as a whole day when its last planned arrival, by
 * its ROUTED travel times, is later than the latest allowed arrival
 * (eqasim-bs ADR-0160, issue #465).
 *
 * The synthesis bounds every day by the simulated day and the population writer
 * of eqasim-bs moves days that leave less than a margin before its end
 * (ADR-0154), but both measure the day with the SURVEY travel times. A long trip
 * whose routed travel time is longer than the survey one (a 132 km car trip home
 * reported with 45 min, routed with 1.35 h) still ends after the simulated day.
 * This step repeats the rule with the routed times, right after the routing:
 *
 * <ul>
 * <li>The planned last arrival replays the plan as the QSim plans it
 * ({@code tryEndTimeThenDuration}): an activity with an end time ends at the
 * later of its end time and the arrival, an activity with only a maximum
 * duration ends that long after the arrival, a leg takes its routed travel
 * time.</li>
 * <li>A plan ending later than the latest arrival moves earlier by its excess
 * rounded up to a whole second: every activity start and end time by the same
 * amount, so every activity and trip duration is kept, as in ADR-0154. A day
 * cannot move before 0 s: the shift is limited by the end time of the first
 * activity, and a plan that could not move far enough is counted as
 * unresolved.</li>
 * <li>The shifted plan is routed again (the caller's re-route), because routes
 * of time-dependent modes (pt) depend on the departure times.</li>
 * </ul>
 *
 * Thread-safe: one instance is shared by the routing threads; the counters are
 * atomic.
 */
public class DayEndShift {
	private final double latestLastArrival;

	private final AtomicLong plans = new AtomicLong();
	private final AtomicLong latePlans = new AtomicLong();
	private final AtomicLong shiftedPlans = new AtomicLong();
	private final AtomicLong unresolvedPlans = new AtomicLong();
	private final AtomicLong shiftSumSeconds = new AtomicLong();
	private final AtomicLong shiftMaxSeconds = new AtomicLong();

	/**
	 * @param latestLastArrival latest planned arrival (s after midnight of the
	 *                          simulated day, finite, &gt;= 0); eqasim-bs passes
	 *                          the end of the simulated day minus the day-end
	 *                          margin
	 */
	public DayEndShift(double latestLastArrival) {
		if (!(latestLastArrival >= 0.0) || Double.isInfinite(latestLastArrival)) {
			throw new IllegalArgumentException(
					"The latest last arrival must be a finite number of seconds >= 0, got " + latestLastArrival);
		}

		this.latestLastArrival = latestLastArrival;
	}

	public double getLatestLastArrival() {
		return latestLastArrival;
	}

	/**
	 * Shifts the plan if it ends too late and then calls {@code reroute}.
	 *
	 * @return the applied shift (s, &gt;= 0)
	 */
	public double apply(Plan plan, Runnable reroute) {
		plans.incrementAndGet();
		double excess = Math.ceil(plannedLastArrival(plan) - latestLastArrival);

		if (!(excess > 0.0)) {
			return 0.0;
		}

		latePlans.incrementAndGet();
		double room = Math.floor(Math.max(0.0, firstActivityEndTime(plan)));
		double shift = Math.min(excess, room);

		if (shift > 0.0) {
			shiftActivities(plan, shift);
			reroute.run();
			shiftedPlans.incrementAndGet();
			shiftSumSeconds.addAndGet((long) shift);
			shiftMaxSeconds.accumulateAndGet((long) shift, Math::max);
		}

		if (shift < excess || plannedLastArrival(plan) > latestLastArrival) {
			// could not move far enough, or the re-routed plan (e.g. a later pt connection) still ends too late
			unresolvedPlans.incrementAndGet();
		}

		return shift;
	}

	/** Last planned arrival (s), replayed as the QSim plans it; NaN for a plan without legs. */
	static public double plannedLastArrival(Plan plan) {
		double time = 0.0;
		double lastArrival = Double.NaN;

		for (PlanElement element : plan.getPlanElements()) {
			if (element instanceof Activity activity) {
				if (activity.getEndTime().isDefined()) {
					time = Math.max(time, activity.getEndTime().seconds());
				} else if (activity.getMaximumDuration().isDefined()) {
					time += activity.getMaximumDuration().seconds();
				}
			} else if (element instanceof Leg leg) {
				time += leg.getTravelTime().orElse(0.0);
				lastArrival = time;
			}
		}

		return lastArrival;
	}

	private static double firstActivityEndTime(Plan plan) {
		Activity first = (Activity) plan.getPlanElements().get(0);
		return first.getEndTime().orElse(0.0);
	}

	private static void shiftActivities(Plan plan, double shift) {
		for (PlanElement element : plan.getPlanElements()) {
			if (element instanceof Activity activity) {
				if (activity.getEndTime().isDefined()) {
					activity.setEndTime(activity.getEndTime().seconds() - shift);
				}

				if (activity.getStartTime().isDefined()) {
					activity.setStartTime(activity.getStartTime().seconds() - shift);
				}
			}
		}
	}

	public long getPlans() {
		return plans.get();
	}

	public long getLatePlans() {
		return latePlans.get();
	}

	public long getShiftedPlans() {
		return shiftedPlans.get();
	}

	public long getUnresolvedPlans() {
		return unresolvedPlans.get();
	}

	public double getMeanShiftSeconds() {
		long shifted = shiftedPlans.get();
		return shifted == 0 ? 0.0 : (double) shiftSumSeconds.get() / shifted;
	}

	public long getMaxShiftSeconds() {
		return shiftMaxSeconds.get();
	}
}
