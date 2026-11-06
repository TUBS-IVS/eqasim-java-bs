package org.eqasim.core.scenario.cutter.population.trips.crossing.transit;

import java.util.LinkedList;
import java.util.List;

import org.eqasim.core.components.transit.departure.DepartureFinder;
import org.eqasim.core.components.transit.departure.DepartureFinder.NoDepartureFoundException;
import org.eqasim.core.components.transit.departure.DepartureFinder.StopDeparture;
import org.eqasim.core.scenario.cutter.extent.ScenarioExtent;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;

import com.google.inject.Inject;

/*
 * Note: this class keeps its own inclusion test per stop pair. It does not use the deprecated
 * StopSequenceCrossingPointFinder, which the schedule cutter has dropped (eqasim-bs#574).
 */
public class DefaultTransitRouteCrossingPointFinder implements TransitRouteCrossingPointFinder {
	/**
	 * Decides whether a position of a route, i.e. the stop at an index of the route's (uncut) stop sequence, lies in
	 * the simulated world. The extent mode tests the stop's coordinate against the extent; the kept-stops mode
	 * (eqasim-bs#574) tests the index against the route's kept range.
	 */
	@FunctionalInterface
	public interface PositionInclusion {
		boolean isIncluded(TransitLine line, TransitRoute route, int index);
	}

	/**
	 * Where a pt leg boards and alights on its route: the indices of the access and the egress stop in the route's
	 * stop sequence and the departure of the route that serves the leg.
	 */
	public record RidePositions(TransitLine line, TransitRoute route, double routeDepartureTime, int accessIndex,
			int egressIndex) {
	}

	final private PositionInclusion inclusion;
	final private TransitSchedule schedule;
	final private DepartureFinder departureFinder;
	final private boolean firstEgressOccurrence;

	@Inject
	public DefaultTransitRouteCrossingPointFinder(ScenarioExtent extent, TransitSchedule schedule,
			DepartureFinder departureFinder) {
		this((line, route, index) -> extent.isInside(route.getStops().get(index).getStopFacility().getCoord()),
				schedule, departureFinder, false);
	}

	/**
	 * @param firstEgressOccurrence where a route serves the egress facility more than once after boarding: true takes
	 *                              the first occurrence (the kept-stops mode, eqasim-bs#574, so that a loop route does
	 *                              not stretch the ride beyond the kept range), false the last one (upstream
	 *                              behaviour of the extent mode)
	 */
	public DefaultTransitRouteCrossingPointFinder(PositionInclusion inclusion, TransitSchedule schedule,
			DepartureFinder departureFinder, boolean firstEgressOccurrence) {
		this.inclusion = inclusion;
		this.schedule = schedule;
		this.departureFinder = departureFinder;
		this.firstEgressOccurrence = firstEgressOccurrence;
	}

	@Override
	public List<TransitRouteCrossingPoint> findCrossingPoints(TransitPassengerRoute route, double departureTime) {
		return findCrossingPoints(findRidePositions(route, departureTime));
	}

	/**
	 * The boarding and alighting positions of a pt leg: the next departure of its route at the access stop from the
	 * leg's departure time on, and the egress stop found by scanning forward from there (its first or last occurrence,
	 * see the constructor).
	 */
	public RidePositions findRidePositions(TransitPassengerRoute route, double departureTime) {
		try {
			TransitLine transitLine = schedule.getTransitLines().get(route.getLineId());
			TransitRoute transitRoute = transitLine.getRoutes().get(route.getRouteId());

			StopDeparture stopDeparture = departureFinder.findNextDeparture(transitRoute, route.getAccessStopId(),
					route.getEgressStopId(), departureTime);

			Departure departure = stopDeparture.departure;
			double routeDepartureTime = departure.getDepartureTime();

			int accessStopIndex = transitRoute.getStops().indexOf(stopDeparture.stop);
			int egressStopIndex = -1;

			if (firstEgressOccurrence) {
				for (int i = accessStopIndex + 1; i < transitRoute.getStops().size(); i++) {
					if (transitRoute.getStops().get(i).getStopFacility().getId().equals(route.getEgressStopId())) {
						egressStopIndex = i;
						break;
					}
				}
			} else {
				for (int i = accessStopIndex; i < transitRoute.getStops().size(); i++) {
					if (transitRoute.getStops().get(i).getStopFacility().getId().equals(route.getEgressStopId())) {
						egressStopIndex = i;
					}
				}
			}

			if (egressStopIndex == -1) {
				throw new IllegalStateException();
			}

			return new RidePositions(transitLine, transitRoute, routeDepartureTime, accessStopIndex, egressStopIndex);
		} catch (NoDepartureFoundException e) {
			throw new IllegalStateException("Did not find departure. This should not happen.");
		}
	}

	/** The in-vehicle crossings of a ride: every pair of consecutive positions of which exactly one is included. */
	public List<TransitRouteCrossingPoint> findCrossingPoints(RidePositions positions) {
		List<TransitRouteCrossingPoint> crossingPoints = new LinkedList<>();

		TransitLine transitLine = positions.line();
		TransitRoute transitRoute = positions.route();
		double routeDepartureTime = positions.routeDepartureTime();
		int accessStopIndex = positions.accessIndex();
		int egressStopIndex = positions.egressIndex();

		List<TransitRouteStop> stops = transitRoute.getStops().subList(accessStopIndex, egressStopIndex + 1);

		for (int i = 0; i < stops.size() - 1; i++) {
			TransitRouteStop firstStop = stops.get(i);
			TransitRouteStop secondStop = stops.get(i + 1);

			boolean firstIsInside = inclusion.isIncluded(transitLine, transitRoute, accessStopIndex + i);
			boolean secondIsInside = inclusion.isIncluded(transitLine, transitRoute, accessStopIndex + i + 1);

			if (firstIsInside != secondIsInside) { // We found a crossing
				TransitRouteStop insideStop = firstIsInside ? firstStop : secondStop;
				TransitRouteStop outsideStop = firstIsInside ? secondStop : firstStop;

				double insideDepartureTime = routeDepartureTime;
				double outsideDepartureTime = routeDepartureTime;

				// This happens if we cross the border only to reach the very last stop of the
				// line.
				if (insideStop.getDepartureOffset().isDefined()) {
					insideDepartureTime += insideStop.getDepartureOffset().seconds();
				} else if (insideStop.getArrivalOffset().isDefined()) {
					insideDepartureTime += insideStop.getArrivalOffset().seconds();
				} else {
					throw new IllegalStateException();
				}

				if (outsideStop.getDepartureOffset().isDefined()) {
					outsideDepartureTime += outsideStop.getDepartureOffset().seconds();
				} else if (outsideStop.getArrivalOffset().isDefined()) {
					outsideDepartureTime += outsideStop.getArrivalOffset().seconds();
				} else {
					throw new IllegalStateException();
				}

				crossingPoints.add(new TransitRouteCrossingPoint(transitLine, transitRoute, outsideStop, insideStop,
						outsideDepartureTime, insideDepartureTime, firstIsInside));
			}
		}

		return crossingPoints;
	}
}
