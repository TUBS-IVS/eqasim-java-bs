package org.eqasim.core.scenario.cutter.population.trips;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.eqasim.core.scenario.cutter.extent.ScenarioExtent;
import org.eqasim.core.scenario.cutter.population.trips.crossing.network.NetworkTripCrossingPoint;
import org.eqasim.core.scenario.cutter.population.trips.crossing.network.NetworkTripCrossingPointFinder;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;

import com.google.inject.Inject;

public class NetworkTripProcessor implements TripProcessor {
	final private NetworkTripCrossingPointFinder crossingPointFinder;
	final private ScenarioExtent extent;
	final private NetworkTripCrossingStatistics statistics;

	@Inject
	public NetworkTripProcessor(NetworkTripCrossingPointFinder crossingPointFinder, ScenarioExtent extent,
			NetworkTripCrossingStatistics statistics) {
		this.crossingPointFinder = crossingPointFinder;
		this.extent = extent;
		this.statistics = statistics;
	}

	@Override
	public List<PlanElement> process(Id<Person> personId, int firstLegIndex, Activity firstActivity,
			List<PlanElement> trip, Activity secondActivity, String routingMode) {
		List<NetworkTripCrossingPoint> crossingPoints = crossingPointFinder.findCrossingPoints(personId, firstLegIndex,
				firstActivity.getCoord(), trip, secondActivity.getCoord());

		if (crossingPoints.size() > 0) { // there are crossing points
			List<PlanElement> result = new LinkedList<>();
			int lastLegIndex = TripStructureUtils.getLegs(trip).size() - 1;

			NetworkTripCrossingPoint firstPoint = crossingPoints.get(0);
			result.add(PopulationUtils.createLeg(firstPoint.isOutgoing
					? insidePieceMode(0, firstPoint.tripLegIndex, firstPoint.legMode, routingMode)
					: "outside"));

			for (int i = 0; i < crossingPoints.size(); i++) {
				NetworkTripCrossingPoint point = crossingPoints.get(i);

				if (point.isInVehicle) {
					Activity activity = PopulationUtils.createActivityFromLinkId("outside",
							point.networkRoutePoint.link.getId());
					activity.setEndTime(point.networkRoutePoint.leaveTime);
					result.add(activity);
				} else {
					Activity activity = PopulationUtils.createActivityFromCoord("outside",
							point.teleportationPoint.coord);
					activity.setEndTime(point.teleportationPoint.time);
					result.add(activity);
				}

				if (point.isOutgoing) {
					result.add(PopulationUtils.createLeg("outside"));
				} else {
					int pieceEndLegIndex = i + 1 < crossingPoints.size() ? crossingPoints.get(i + 1).tripLegIndex
							: lastLegIndex;
					result.add(PopulationUtils.createLeg(
							insidePieceMode(point.tripLegIndex, pieceEndLegIndex, point.legMode, routingMode)));
				}
			}

			return result;
		} else { // there are no crossing points
			if (crossingPointFinder.isInside(trip)) { // whole trip is inside
				return Arrays.asList(PopulationUtils.createLeg(routingMode));
			} else {
				// The route is outside. This does not mean that both (or any) activity is
				// actually outside. These are mainly special cases in which the route is
				// outside, but some activity is inside, across the border, because the link is
				// parallel to the border. We put an outside activity right next to the inside
				// activity.

				List<PlanElement> result = new LinkedList<>();

				result.add(PopulationUtils.createLeg("outside"));

				if (extent.isInside(firstActivity.getCoord())) {
					Activity activity = PopulationUtils.createActivityFromLinkId("outside", firstActivity.getLinkId());
					activity.setEndTime(firstActivity.getEndTime().seconds());
					result.add(activity);

					result.add(PopulationUtils.createLeg("outside"));
				}

				if (extent.isInside(secondActivity.getCoord())) {
					Activity activity = PopulationUtils.createActivityFromLinkId("outside", secondActivity.getLinkId());
					activity.setEndTime(secondActivity.getStartTime().seconds());
					result.add(activity);

					result.add(PopulationUtils.createLeg("outside"));
				}

				return result;
			}
		}
	}

	/**
	 * Mode of an inside piece of a border-crossing network trip that starts on the
	 * leg with trip index startLegIndex (the trip start or an incoming crossing) and
	 * ends on the leg with trip index endLegIndex (an outgoing crossing or the trip
	 * end).
	 * 
	 * A piece within one access or egress leg keeps that leg's mode: it is the short
	 * walk between the border and an activity. Any other piece contains the main
	 * network leg and keeps the trip's routing mode, so that the router routes the
	 * inside part by car again. Before eqasim-bs#465, every inside piece took the
	 * mode of the leg it crossed the border on, so a car trip from a home just
	 * outside the border, whose access walk crosses it, became one walk across the
	 * whole inside part.
	 */
	private String insidePieceMode(int startLegIndex, int endLegIndex, String crossingLegMode, String routingMode) {
		if (startLegIndex == endLegIndex && !crossingLegMode.equals(routingMode)) {
			statistics.recordAccessEgressOnlyPiece();
			return crossingLegMode;
		}

		statistics.recordRoutingModePiece(!crossingLegMode.equals(routingMode));
		return routingMode;
	}
}
