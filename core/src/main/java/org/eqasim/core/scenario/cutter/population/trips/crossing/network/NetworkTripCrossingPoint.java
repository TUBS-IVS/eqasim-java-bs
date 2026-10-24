package org.eqasim.core.scenario.cutter.population.trips.crossing.network;

import org.eqasim.core.scenario.cutter.population.trips.crossing.teleportation.TeleportationCrossingPoint;

public class NetworkTripCrossingPoint {
	final public boolean isInVehicle;
	final public boolean isOutgoing;

	final public NetworkRouteCrossingPoint networkRoutePoint;
	final public TeleportationCrossingPoint teleportationPoint;

	final public String legMode;

	/**
	 * Position (0-based) of the crossing leg among the legs of the trip, e.g. 0 for
	 * the access walk, 1 for the car leg and 2 for the egress walk of a routed car
	 * trip. NetworkTripProcessor uses it to tell an inside piece that lies within a
	 * single access or egress leg apart from one that contains the main network leg
	 * (eqasim-bs#465).
	 */
	final public int tripLegIndex;

	public NetworkTripCrossingPoint(NetworkRouteCrossingPoint networkRoutePoint, String legMode, int tripLegIndex) {
		this.isInVehicle = true;
		this.networkRoutePoint = networkRoutePoint;
		this.teleportationPoint = null;
		this.isOutgoing = networkRoutePoint.isOutgoing;
		this.legMode = legMode;
		this.tripLegIndex = tripLegIndex;
	}

	public NetworkTripCrossingPoint(TeleportationCrossingPoint teleportationPoint, String legMode, int tripLegIndex) {
		this.isInVehicle = false;
		this.networkRoutePoint = null;
		this.teleportationPoint = teleportationPoint;
		this.isOutgoing = teleportationPoint.isOutgoing;
		this.legMode = legMode;
		this.tripLegIndex = tripLegIndex;
	}
}
