package org.eqasim.core.scenario.cutter.population.trips.crossing.teleportation;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

public class TeleportationCrossingPoint {
	final public Coord coord;
	final public double time;
	final public boolean isOutgoing;

	/**
	 * The stop facility the point lies at, or null for a crossing of the extent polygon (the default finders never
	 * set it). The kept-stops transit finder sets it on a walk transition at a stop (eqasim-bs#574).
	 */
	final public Id<TransitStopFacility> stopFacilityId;

	public TeleportationCrossingPoint(Coord coord, double time, boolean isOutgoing) {
		this(coord, time, isOutgoing, null);
	}

	public TeleportationCrossingPoint(Coord coord, double time, boolean isOutgoing,
			Id<TransitStopFacility> stopFacilityId) {
		this.coord = coord;
		this.time = time;
		this.isOutgoing = isOutgoing;
		this.stopFacilityId = stopFacilityId;
	}
}
