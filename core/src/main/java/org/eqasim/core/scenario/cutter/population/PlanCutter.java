package org.eqasim.core.scenario.cutter.population;

import java.util.LinkedList;
import java.util.List;

import org.eqasim.core.misc.Constants;
import org.eqasim.core.scenario.cutter.extent.ScenarioExtent;
import org.eqasim.core.scenario.cutter.population.trips.TripProcessor;
import org.eqasim.core.scenario.routing.PlanRouter;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;

import com.google.inject.Inject;

public class PlanCutter {
	private final ScenarioExtent extent;
	private final TripProcessor tripProcessor;
	private final MergeOutsideActivities mergeOutsideActivities;

	@Inject
	public PlanCutter(TripProcessor tripProcessor, ScenarioExtent extent,
			MergeOutsideActivities mergeOutsideActivities) {
		this.extent = extent;
		this.tripProcessor = tripProcessor;
		this.mergeOutsideActivities = mergeOutsideActivities;
	}

	private void addActivity(List<PlanElement> plan, Activity activity) {
		if (extent.isInside(activity.getCoord())) {
			plan.add(activity);
		} else {
			Activity virtualActivity = PopulationUtils.createActivityFromCoord(Constants.OUTSIDE_ACTIVITY_TYPE,
					activity.getCoord());

			if (activity.getEndTime().isDefined()) {
				virtualActivity.setEndTime(activity.getEndTime().seconds());
			} else {
				virtualActivity.setEndTimeUndefined();
			}

			virtualActivity.getAttributes().putAttribute(Constants.TYPE_BEFORE_CUTTING_ATTRIBUTE, activity.getType());

			plan.add(virtualActivity);
		}
	}

	/**
	 * The trip processors build the cut trip from fresh legs, which carry no
	 * attributes. A trip the plan router had replaced by a walk carries the
	 * walk-fallback stamp; without this copy the final routing re-routes it as a
	 * plain walk and the stamp, i.e. the information that the trip was requested
	 * as pt, is lost for the plan check (eqasim-bs#487). Outside legs are not
	 * simulated as walks and stay unstamped.
	 */
	static void keepWalkFallbackStamp(List<Leg> originalLegs, List<PlanElement> processed) {
		Object walkFallbackFrom = null;

		for (Leg leg : originalLegs) {
			walkFallbackFrom = leg.getAttributes().getAttribute(PlanRouter.WALK_FALLBACK_ATTRIBUTE);

			if (walkFallbackFrom != null) {
				break;
			}
		}

		if (walkFallbackFrom != null) {
			for (PlanElement element : processed) {
				if (element instanceof Leg leg && !leg.getMode().equals(Constants.OUTSIDE_MODE)) {
					leg.getAttributes().putAttribute(PlanRouter.WALK_FALLBACK_ATTRIBUTE, walkFallbackFrom);
				}
			}
		}
	}

	public List<PlanElement> processPlan(Id<Person> personId, List<PlanElement> elements) {
		List<PlanElement> result = new LinkedList<>();

		if (elements.size() > 0) {
			addActivity(result, (Activity) elements.get(0));
			int legIndex = 0;

			for (TripStructureUtils.Trip trip : TripStructureUtils.getTrips(elements)) {
				String routingMode = TripStructureUtils.getRoutingMode(trip.getLegsOnly().get(0));

				List<PlanElement> processed = tripProcessor.process(personId, legIndex, trip.getOriginActivity(),
						trip.getTripElements(), trip.getDestinationActivity(), routingMode);
				keepWalkFallbackStamp(trip.getLegsOnly(), processed);
				result.addAll(processed);

				addActivity(result, trip.getDestinationActivity());
				legIndex += trip.getLegsOnly().size();
			}
		}

		mergeOutsideActivities.run(result);

		return result;
	}
}
