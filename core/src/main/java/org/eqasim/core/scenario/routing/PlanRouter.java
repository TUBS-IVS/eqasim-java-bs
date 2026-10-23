package org.eqasim.core.scenario.routing;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.facilities.Facility;
import org.matsim.utils.objectattributes.attributable.Attributes;

import com.google.inject.Inject;

public class PlanRouter {
	private final ActivityFacilities facilities;
	private final TripRouter tripRouter;

	/**
	 * Leg attribute set on every leg of a trip that was replaced by a plain walk
	 * because the routed trip of the given main mode contained only walk legs. The
	 * value is the original main mode (e.g. "pt").
	 */
	public static final String WALK_FALLBACK_ATTRIBUTE = "walkFallbackFrom";

	// Not thread-safe by design: one PlanRouter instance is used by one thread.
	private final Map<String, Integer> walkFallbackCounts = new HashMap<>();
	private final Map<String, Integer> routedTripCounts = new HashMap<>();
	private final Map<String, Integer> keptWalkFallbackCounts = new HashMap<>();

	@Inject
	public PlanRouter(ActivityFacilities facilities, TripRouter tripRouter) {
		this.tripRouter = tripRouter;
		this.facilities = facilities;
	}

	public void run(Plan plan, boolean replaceExistingRoutes, Set<String> modes) {
		List<Trip> trips = TripStructureUtils.getTrips(plan);

		for (Trip trip : trips) {
			boolean legsExist = true;

			for (Leg leg : trip.getLegsOnly()) {
				if (leg.getRoute() == null) {
					legsExist = false;
				}
			}

			if (!legsExist || replaceExistingRoutes) {
				String mainMode = TripStructureUtils.identifyMainMode(trip.getTripElements());

				if (modes.size() == 0 || modes.contains(mainMode)) {
					OptionalTime departureTime = trip.getOriginActivity().getEndTime();

					if (departureTime.isUndefined()) {
						throw new IllegalStateException(
								"Found undefined activity end time for agent " + plan.getPerson().getId().toString());
					}

					Facility fromFacility = FacilitiesUtils.toFacility(trip.getOriginActivity(), facilities);
					Facility toFacility = FacilitiesUtils.toFacility(trip.getDestinationActivity(), facilities);

					List<? extends PlanElement> newElements = tripRouter.calcRoute(mainMode, fromFacility, toFacility,
							departureTime.seconds(), plan.getPerson(), trip.getTripAttributes());

					routedTripCounts.merge(mainMode, 1, Integer::sum);

					// Fix in case we have a transit trip that is only walk
					newElements = fixOnlyWalk(mainMode, fromFacility, toFacility, departureTime.seconds(),
							plan.getPerson(), newElements, trip.getTripAttributes());

					keepWalkFallbackStamp(trip.getLegsOnly(), newElements);

					TripRouter.insertTrip(plan, trip.getOriginActivity(), newElements, trip.getDestinationActivity());
				}
			}
		}
	}

	/**
	 * Re-stamps a walk trip that an earlier routing pass had replaced by a walk.
	 * A later pass (e.g. the final routing of the scenario cutter, which drops the
	 * routes) sees such a trip as a plain walk and would replace its stamped legs
	 * by unstamped ones (eqasim-bs#487). The stamp is kept only while the trip is
	 * still walk-only and was not stamped anew by this pass.
	 */
	private void keepWalkFallbackStamp(List<Leg> previousLegs, List<? extends PlanElement> newElements) {
		Object walkFallbackFrom = null;

		for (Leg leg : previousLegs) {
			walkFallbackFrom = leg.getAttributes().getAttribute(WALK_FALLBACK_ATTRIBUTE);

			if (walkFallbackFrom != null) {
				break;
			}
		}

		if (walkFallbackFrom == null) {
			return;
		}

		List<Leg> newLegs = TripStructureUtils.getLegs(newElements);

		for (Leg leg : newLegs) {
			if (!leg.getMode().equals(TransportMode.walk)
					|| leg.getAttributes().getAttribute(WALK_FALLBACK_ATTRIBUTE) != null) {
				return;
			}
		}

		for (Leg leg : newLegs) {
			leg.getAttributes().putAttribute(WALK_FALLBACK_ATTRIBUTE, walkFallbackFrom);
		}

		keptWalkFallbackCounts.merge(walkFallbackFrom.toString(), 1, Integer::sum);
	}

	private List<? extends PlanElement> fixOnlyWalk(String mainMode, Facility fromFacility, Facility toFacility,
			double departureTime, Person person, List<? extends PlanElement> elements, Attributes attributes) {
		// No need to fix if already walk
		if (mainMode.equals(TransportMode.walk)) {
			return elements;
		}

		// No need to fix if we have a non-walk leg
		for (Leg leg : TripStructureUtils.getLegs(elements)) {
			if (!leg.getMode().equals(TransportMode.walk)) {
				return elements;
			}
		}

		// We have only walk legs: replace by a plain walk, but make it observable
		walkFallbackCounts.merge(mainMode, 1, Integer::sum);

		List<? extends PlanElement> replacement = tripRouter.calcRoute(TransportMode.walk, fromFacility, toFacility,
				departureTime, person, attributes);

		for (Leg leg : TripStructureUtils.getLegs(replacement)) {
			leg.getAttributes().putAttribute(WALK_FALLBACK_ATTRIBUTE, mainMode);
		}

		return replacement;
	}

	/** The counters of a router at one moment, see {@link #snapshotCounts()}. */
	static public class CountSnapshot {
		private final Map<String, Integer> walkFallbackCounts;
		private final Map<String, Integer> routedTripCounts;
		private final Map<String, Integer> keptWalkFallbackCounts;

		private CountSnapshot(PlanRouter router) {
			this.walkFallbackCounts = new HashMap<>(router.walkFallbackCounts);
			this.routedTripCounts = new HashMap<>(router.routedTripCounts);
			this.keptWalkFallbackCounts = new HashMap<>(router.keptWalkFallbackCounts);
		}
	}

	/**
	 * The current counters, so that a caller that routes a plan again and keeps
	 * only the second result (the routed day-end shift, eqasim-bs ADR-0160) can
	 * {@link #restoreCounts restore} them first: the counts then describe the
	 * routed population, every trip once.
	 */
	public CountSnapshot snapshotCounts() {
		return new CountSnapshot(this);
	}

	public void restoreCounts(CountSnapshot snapshot) {
		walkFallbackCounts.clear();
		walkFallbackCounts.putAll(snapshot.walkFallbackCounts);
		routedTripCounts.clear();
		routedTripCounts.putAll(snapshot.routedTripCounts);
		keptWalkFallbackCounts.clear();
		keptWalkFallbackCounts.putAll(snapshot.keptWalkFallbackCounts);
	}

	/**
	 * Turns every trip that a routing pass replaced by a walk (legs stamped with
	 * {@link #WALK_FALLBACK_ATTRIBUTE}) back into one unrouted leg of the
	 * originally requested mode, so that routing the plan again tries that mode at
	 * the plan's (possibly new) departure times instead of keeping the walk. Used
	 * before the routed day-end shift re-routes a plan whose times it moved
	 * (eqasim-bs ADR-0160).
	 *
	 * @return the number of trips restored
	 */
	public static int restoreWalkFallbackModes(Plan plan) {
		int restored = 0;

		for (Trip trip : TripStructureUtils.getTrips(plan)) {
			Object originalMode = null;

			for (Leg leg : trip.getLegsOnly()) {
				originalMode = leg.getAttributes().getAttribute(WALK_FALLBACK_ATTRIBUTE);

				if (originalMode != null) {
					break;
				}
			}

			if (originalMode != null) {
				Leg leg = PopulationUtils.createLeg(originalMode.toString());
				TripStructureUtils.setRoutingMode(leg, originalMode.toString());
				TripRouter.insertTrip(plan, trip.getOriginActivity(), List.of(leg), trip.getDestinationActivity());
				restored++;
			}
		}

		return restored;
	}

	/** Trips replaced by a plain walk so far, per original main mode. */
	public Map<String, Integer> getWalkFallbackCounts() {
		return Collections.unmodifiableMap(walkFallbackCounts);
	}

	/**
	 * Walk trips re-routed so far that carried the stamp of an earlier routing
	 * pass and kept it, per stamped (originally requested) mode.
	 */
	public Map<String, Integer> getKeptWalkFallbackCounts() {
		return Collections.unmodifiableMap(keptWalkFallbackCounts);
	}

	/** Trips routed so far (including replaced ones), per original main mode. */
	public Map<String, Integer> getRoutedTripCounts() {
		return Collections.unmodifiableMap(routedTripCounts);
	}
}
