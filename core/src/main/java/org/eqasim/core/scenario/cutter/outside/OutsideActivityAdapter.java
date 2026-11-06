package org.eqasim.core.scenario.cutter.outside;

import org.eqasim.core.misc.Constants;
import org.eqasim.core.scenario.cutter.network.RoadNetwork;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.network.NetworkUtils;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/**
 * Attaches every {@code outside} activity of the population to a facility on the road network. An activity that the
 * transit cut created at a public-transport stop (attribute {@link Constants#CUT_TRANSIT_STOP_ATTRIBUTE}, kept-stops
 * mode, eqasim-bs#574) keeps its coordinate (the stop's) and gets one facility per stop, so that the re-routed pt piece
 * starts or ends at the stop without an access walk the planned departure did not include; every other outside
 * activity moves to one facility per nearest road link, at the link's midpoint. Both are linked to the nearest road
 * link, from which a car leg is routable.
 *
 * Runs single-threaded on the main thread after the population cutter joined its workers, so plain maps and counters
 * suffice.
 */
public class OutsideActivityAdapter {
	final private RoadNetwork roadNetwork;

	private long outsideActivities = 0;
	private long stopAnchoredActivities = 0;

	public OutsideActivityAdapter(RoadNetwork roadNetwork) {
		this.roadNetwork = roadNetwork;
	}

	public void run(Population population, ActivityFacilities facilities) {
		OutsideFacilityAdapter facilityAdapter = new OutsideFacilityAdapter(facilities);

		for (Person person : population.getPersons().values()) {
			for (Plan plan : person.getPlans()) {
				for (PlanElement element : plan.getPlanElements()) {
					if (element instanceof Activity) {
						Activity activity = (Activity) element;

						if (activity.getType().equals("outside")) {
							Link activityLink;

							if (activity.getLinkId() != null) {
								activityLink = roadNetwork.getLinks().get(activity.getLinkId());
							} else {
								activityLink = NetworkUtils.getNearestLink(roadNetwork, activity.getCoord());
							}

							Object stopId = activity.getAttributes().getAttribute(Constants.CUT_TRANSIT_STOP_ATTRIBUTE);
							ActivityFacility facility;

							if (stopId != null) {
								// The stop's coordinate stays; the coordinate is set below unchanged
								facility = facilityAdapter.getStopFacility(
										Id.create(stopId.toString(), TransitStopFacility.class), activity.getCoord(),
										activityLink);
								stopAnchoredActivities++;
							} else {
								facility = facilityAdapter.getFacility(activityLink);
							}

							outsideActivities++;

							activity.setCoord(facility.getCoord());
							activity.setLinkId(facility.getLinkId());
							activity.setFacilityId(facility.getId());
						}
					}
				}

				Activity lastActivity = (Activity) plan.getPlanElements().get(plan.getPlanElements().size() - 1);

				if (lastActivity.getType().equals("outside")) {
					lastActivity.setEndTimeUndefined();
				}
			}
		}
	}

	/** The outside activities adapted by {@link #run} so far. */
	public long getOutsideActivities() {
		return outsideActivities;
	}

	/** The outside activities that {@link #run} anchored at a stop so far (a subset of the outside activities). */
	public long getStopAnchoredActivities() {
		return stopAnchoredActivities;
	}
}