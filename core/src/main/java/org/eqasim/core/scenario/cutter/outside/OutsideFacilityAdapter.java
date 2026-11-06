package org.eqasim.core.scenario.cutter.outside;

import java.util.HashMap;
import java.util.Map;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.ActivityOption;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/**
 * Creates the facilities of the {@code outside} activities and adds them to the facilities container: one per road
 * link at the link's midpoint ({@link #getFacility}), and one per public-transport stop at the stop's coordinate
 * ({@link #getStopFacility}, eqasim-bs#574). Not thread-safe.
 */
public class OutsideFacilityAdapter {
	static final String STOP_FACILITY_PREFIX = "outside_stop_";

	final private ActivityFacilities facilities;
	final private Map<Link, ActivityFacility> facilitiesByLink = new HashMap<>();
	final private Map<Id<TransitStopFacility>, ActivityFacility> facilitiesByStop = new HashMap<>();

	private int counter = 0;

	public OutsideFacilityAdapter(ActivityFacilities facilities) {
		this.facilities = facilities;
	}

	private Id<ActivityFacility> createNextId() {
		while (true) {
			counter++;
			var id = Id.create("outside_" + counter, ActivityFacility.class);
			if (!facilities.getFacilities().containsKey(id)) {
				return id;
			}
		}
	}

	public ActivityFacility getFacility(Link link) {
		ActivityFacility facility = facilitiesByLink.get(link);

		if (facility == null) {
			facility = facilities.getFactory().createActivityFacility(createNextId(), link.getCoord(), link.getId());

			ActivityOption option = facilities.getFactory().createActivityOption("outside");
			facility.addActivityOption(option);

			facilitiesByLink.put(link, facility);
			facilities.addActivityFacility(facility);
		}

		return facility;
	}

	/**
	 * The facility of the outside activities at the stop {@code stopId}: created on the first call (id
	 * {@code outside_stop_<stop id>}, coordinate {@code coord}, link {@code link}, activity option {@code outside})
	 * and added to the facilities, returned as is on later calls.
	 *
	 * @param coord the coordinate of the stop (the outside activity's coordinate)
	 * @param link  the road link the facility is attached to (a car leg must be routable from there)
	 * @throws IllegalStateException if the facility of the stop exists with another coordinate, or if the input
	 *                               facilities already hold the id {@code outside_stop_<stop id>}
	 */
	public ActivityFacility getStopFacility(Id<TransitStopFacility> stopId, Coord coord, Link link) {
		ActivityFacility facility = facilitiesByStop.get(stopId);

		if (facility != null) {
			if (!facility.getCoord().equals(coord)) {
				throw new IllegalStateException("The outside activities at stop " + stopId
						+ " lie at two coordinates: " + facility.getCoord() + " and " + coord);
			}

			return facility;
		}

		Id<ActivityFacility> facilityId = Id.create(STOP_FACILITY_PREFIX + stopId, ActivityFacility.class);

		if (facilities.getFacilities().containsKey(facilityId)) {
			throw new IllegalStateException("The facilities already hold the id " + facilityId
					+ " of the outside facility of stop " + stopId + " (at " + coord + "): refusing to reuse it");
		}

		facility = facilities.getFactory().createActivityFacility(facilityId, coord, link.getId());

		ActivityOption option = facilities.getFactory().createActivityOption("outside");
		facility.addActivityOption(option);

		facilitiesByStop.put(stopId, facility);
		facilities.addActivityFacility(facility);

		return facility;
	}
}
