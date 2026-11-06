package org.eqasim.core.scenario.cutter.population.trips.crossing.transit;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.eqasim.core.components.transit.departure.DepartureFinder;
import org.eqasim.core.scenario.cutter.extent.ScenarioExtent;
import org.eqasim.core.scenario.cutter.population.trips.crossing.teleportation.TeleportationCrossingPoint;
import org.eqasim.core.scenario.cutter.population.trips.crossing.transit.DefaultTransitRouteCrossingPointFinder.RidePositions;
import org.eqasim.core.scenario.cutter.transit.TransitScheduleCutter;
import org.eqasim.core.scenario.cutter.transit.TransitScheduleCutter.KeptRange;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/**
 * Finds the crossing points of a pt trip at the boundary of the KEPT timetable instead of the extent polygon
 * (eqasim-bs#574, kept-stops mode of the scenario cutter).
 *
 * The simulated world of a pt trip consists of
 * <ul>
 * <li>the activities inside the extent (the origin and destination of the trip, tested with the extent),</li>
 * <li>the route POSITIONS {@code (line, route, index)} that the cut timetable keeps: a position is in the world
 * iff its route has a kept range (see {@link org.eqasim.core.scenario.cutter.transit.TransitScheduleCutter#keptStopRanges})
 * and {@code first <= index <= last}. Membership is per position, not per stop facility: a ride on a route that the
 * cut drops is outside the world even between two kept facilities.</li>
 * </ul>
 *
 * The finder walks along the points of the trip (origin, boarding and alighting positions of every pt leg,
 * destination) and reports each membership transition once:
 * <ul>
 * <li>in the vehicle: a {@link TransitRouteCrossingPoint} between two consecutive positions of a ride, exactly as in
 * the extent mode but with the position test instead of the polygon test;</li>
 * <li>on a walk (or any other non-pt leg) whose end points differ: one {@link TeleportationCrossingPoint} at the end
 * point that is in the world. An exit is outgoing at the walk's from-point at the walk's departure time; an entry is
 * incoming at the walk's to-point at the walk's arrival time (departure plus travel time), so the boarding wait at an
 * entry stop is simulated.</li>
 * </ul>
 * The end point of a walk next to a pt leg is that leg's boarding (or alighting) position; an end point between two
 * consecutive non-pt legs is the stage activity there, tested with the extent like an activity. Two pt legs without a
 * walk between them, a trip starting with a pt leg and a trip ending with a pt leg change membership at the stop
 * itself: the transition is reported at that stop at the next leg's departure time (or, at the end of the trip, at the
 * last leg's arrival time). Because every pair of consecutive points is tested exactly once, the reported points
 * alternate between outgoing and incoming, starting with outgoing iff the origin is inside the extent.
 *
 * A walk transition that lies at a stop (the boarding stop of a ride, the alighting stop of the ride before a walk,
 * or the boarding stop of the ride after a walk) carries that stop's facility id
 * ({@link TeleportationCrossingPoint#stopFacilityId}), so that the outside activity built there can be anchored at
 * the stop; a transition at the origin, the destination or a stage activity between two non-pt legs carries none.
 */
public class KeptStopsTransitTripCrossingPointFinder implements TransitTripCrossingPointFinder {
	private final ScenarioExtent extent;
	private final Map<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> keptRanges;
	private final DefaultTransitRouteCrossingPointFinder routeFinder;

	/**
	 * @param preCutSchedule  the schedule BEFORE the cut: the trips' routes and the ranges' indices refer to it
	 * @param departureFinder finds the departure that serves a pt leg
	 * @param extent          the extent that decides the membership of activities
	 * @param keptRanges      the kept index range per kept route of the pre-cut schedule (the dry pass of the
	 *                        schedule cut); a route absent from it is dropped by the cut
	 * @throws IllegalStateException    if a range ends beyond its route's stop sequence (the schedule is already cut)
	 * @throws IllegalArgumentException if the ranges name a line or route the schedule lacks
	 */
	public KeptStopsTransitTripCrossingPointFinder(TransitSchedule preCutSchedule, DepartureFinder departureFinder,
			ScenarioExtent extent, Map<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> keptRanges) {
		checkPreCutSchedule(preCutSchedule, keptRanges);
		this.extent = extent;
		this.keptRanges = Collections.unmodifiableMap(keptRanges);
		// A loop route that serves the egress facility again beyond the kept range must not stretch the ride
		this.routeFinder = new DefaultTransitRouteCrossingPointFinder(this::isKept, preCutSchedule, departureFinder,
				true);
	}

	/**
	 * The ranges index the stop sequences of the UNCUT schedule; a cut schedule (shorter sequences, dropped routes)
	 * would silently shift every position. Fails if a range ends beyond its route's stops, or (through
	 * {@link TransitScheduleCutter#keptStopFacilityIds}) if a line or route of the ranges is missing.
	 */
	static void checkPreCutSchedule(TransitSchedule schedule,
			Map<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> keptRanges) {
		keptRanges.forEach((lineId, routes) -> {
			TransitLine line = schedule.getTransitLines().get(lineId);

			routes.forEach((routeId, range) -> {
				TransitRoute route = line == null ? null : line.getRoutes().get(routeId);

				if (route != null && range.last() >= route.getStops().size()) {
					throw new IllegalStateException("The kept range " + range + " of route " + routeId + " of line "
							+ lineId + " ends beyond its " + route.getStops().size()
							+ " stops: the schedule is not the uncut one the ranges were computed on");
				}
			});
		});

		TransitScheduleCutter.keptStopFacilityIds(schedule, keptRanges);
	}

	/** Whether the position {@code index} of the route lies in the kept timetable. */
	boolean isKept(TransitLine line, TransitRoute route, int index) {
		Map<Id<TransitRoute>, KeptRange> routes = keptRanges.get(line.getId());

		if (routes == null) {
			return false;
		}

		KeptRange range = routes.get(route.getId());
		return range != null && range.first() <= index && index <= range.last();
	}

	private boolean isKeptAccess(RidePositions positions) {
		return isKept(positions.line(), positions.route(), positions.accessIndex());
	}

	private boolean isKeptEgress(RidePositions positions) {
		return isKept(positions.line(), positions.route(), positions.egressIndex());
	}

	private static Coord accessCoord(RidePositions positions) {
		return positions.route().getStops().get(positions.accessIndex()).getStopFacility().getCoord();
	}

	private static Coord egressCoord(RidePositions positions) {
		return positions.route().getStops().get(positions.egressIndex()).getStopFacility().getCoord();
	}

	private static Id<TransitStopFacility> accessStopId(RidePositions positions) {
		return positions.route().getStops().get(positions.accessIndex()).getStopFacility().getId();
	}

	private static Id<TransitStopFacility> egressStopId(RidePositions positions) {
		return positions.route().getStops().get(positions.egressIndex()).getStopFacility().getId();
	}

	private static TransitPassengerRoute transitRoute(PlanElement element) {
		if (element instanceof Leg leg && leg.getRoute() instanceof TransitPassengerRoute route) {
			return route;
		}

		return null;
	}

	/** The index of the next leg after {@code index}, or -1 if there is none. */
	private static int nextLegIndex(List<PlanElement> trip, int index) {
		for (int i = index + 1; i < trip.size(); i++) {
			if (trip.get(i) instanceof Leg) {
				return i;
			}
		}

		return -1;
	}

	@Override
	public List<TransitTripCrossingPoint> findCrossingPoints(Coord startCoord, List<PlanElement> trip, Coord endCoord) {
		List<TransitTripCrossingPoint> result = new LinkedList<>();

		// The ride positions of every pt leg, computed once (the walks next to a ride need them, too)
		RidePositions[] rides = new RidePositions[trip.size()];

		for (int i = 0; i < trip.size(); i++) {
			TransitPassengerRoute route = transitRoute(trip.get(i));

			if (route != null) {
				rides[i] = routeFinder.findRidePositions(route, ((Leg) trip.get(i)).getDepartureTime().seconds());
			}
		}

		// The membership, coordinate and stop facility (null for an activity) of the last point passed
		boolean inWorld = extent.isInside(startCoord);
		Coord coord = startCoord;
		Id<TransitStopFacility> stopId = null;
		double arrivalTime = Double.NaN;
		boolean endsWithRide = false;

		for (int i = 0; i < trip.size(); i++) {
			if (!(trip.get(i) instanceof Leg leg)) {
				continue;
			}

			RidePositions ride = rides[i];
			endsWithRide = ride != null;

			if (ride != null) {
				// Boarding without a walk before (first leg, or a pt leg right after a pt leg): the transition, if
				// any, happens at the boarding stop when the leg departs
				boolean boardingInWorld = isKeptAccess(ride);

				if (boardingInWorld != inWorld) {
					result.add(new TransitTripCrossingPoint(new TeleportationCrossingPoint(accessCoord(ride),
							leg.getDepartureTime().seconds(), inWorld, accessStopId(ride))));
				}

				routeFinder.findCrossingPoints(ride).forEach(p -> result.add(new TransitTripCrossingPoint(p)));

				inWorld = isKeptEgress(ride);
				coord = egressCoord(ride);
				stopId = egressStopId(ride);
				arrivalTime = leg.getDepartureTime().seconds() + leg.getTravelTime().seconds();
			} else {
				int next = nextLegIndex(trip, i);
				boolean toInWorld;
				Coord toCoord;
				Id<TransitStopFacility> toStopId = null;

				if (next < 0) {
					toInWorld = extent.isInside(endCoord);
					toCoord = endCoord;
				} else if (rides[next] != null) {
					toInWorld = isKeptAccess(rides[next]);
					toCoord = accessCoord(rides[next]);
					toStopId = accessStopId(rides[next]);
				} else {
					toCoord = ((Activity) trip.get(i + 1)).getCoord();
					toInWorld = extent.isInside(toCoord);
				}

				double departureTime = leg.getDepartureTime().seconds();

				if (inWorld && !toInWorld) {
					// Exit: leaves the world at the from-point when the walk departs
					result.add(new TransitTripCrossingPoint(
							new TeleportationCrossingPoint(coord, departureTime, true, stopId)));
				} else if (!inWorld && toInWorld) {
					// Entry: enters the world at the to-point when the walk arrives
					result.add(new TransitTripCrossingPoint(new TeleportationCrossingPoint(toCoord,
							departureTime + leg.getTravelTime().seconds(), false, toStopId)));
				}

				inWorld = toInWorld;
				coord = toCoord;
				stopId = toStopId;
			}
		}

		// Ending with a pt leg: the transition to the destination, if any, happens at the alighting stop on arrival
		// (after a non-pt last leg the membership already is the destination's)
		if (endsWithRide && extent.isInside(endCoord) != inWorld) {
			result.add(new TransitTripCrossingPoint(new TeleportationCrossingPoint(coord, arrivalTime, inWorld,
					stopId)));
		}

		return result;
	}
}
