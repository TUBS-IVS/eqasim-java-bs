package org.eqasim.core.components.transit.departure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eqasim.core.components.transit.departure.DepartureFinder.NoDepartureFoundException;
import org.eqasim.core.components.transit.departure.DepartureFinder.StopDeparture;
import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/**
 * Chooses the departure a passenger boards in the schedule-based transit engine
 * (eqasim-bs#465).
 *
 * <ol>
 * <li>The planned route, searched from {@code now - boardingToleranceSeconds}.
 * The pt router plans transfers of zero duration (a zero-length walk between two
 * stop facilities at the same place, with
 * {@code transitRouter.additionalTransferTime} 0), but the QSim teleports that
 * walk in one time step: the passenger reaches the next stop one second after
 * the planned departure. Without the tolerance the passenger misses a departure
 * the plan was built on and waits for the next departure of the route, or is
 * stuck when the route has none left.</li>
 * <li>When the planned route has no departure left and the line fallback is
 * enabled: the other routes of the same line that serve the access stop and,
 * later, the egress stop; the earliest vehicle departure wins (ties: the route
 * id in lexical order). This covers passengers delayed earlier in the day (for
 * example by road congestion), who in reality board the next vehicle of their
 * line, not only the next one of the route variant the router picked.</li>
 * <li>With the stop-pair fallback (ADR-0161 of eqasim-bs), which replaces the
 * two steps above for a passenger who MISSED the planned departure (the next
 * vehicle of the planned route leaves after the planned boarding time, or the
 * route has none left): every route of every line that serves the access stop
 * and, later, the egress stop is a candidate, the planned route included, and
 * the earliest arrival at the egress stop wins (ties: the planned route, then
 * the planned line, then line id and route id in lexical order). A passenger who
 * missed the last vehicle of one line boards the evening line on the same two
 * stops instead of waiting for the first vehicle of the next day. A passenger on
 * time keeps the planned route, even when another line would arrive earlier,
 * so the plan the mode choice evaluated is the one executed.</li>
 * </ol>
 *
 * Throws {@link NoDepartureFoundException} when no candidate exists; the engine
 * then aborts the agent as before.
 */
public class TransitBoardingFinder {
	private final DepartureFinder departureFinder;
	private final double boardingToleranceSeconds;
	private final boolean lineFallbackEnabled;
	private final boolean stopPairFallbackEnabled;

	/** Routes of every line per stop facility, for the stop-pair fallback (empty when it is off). */
	private final Map<Id<TransitStopFacility>, List<LineRoute>> routesByStop;

	/** The departure a passenger boards. */
	static public class Boarding {
		/** The line of the boarded route (the planned line unless the stop-pair fallback chose another). */
		public final TransitLine line;
		public final TransitRoute route;
		public final StopDeparture stopDeparture;
		/** Departure time of the vehicle at the access stop (s). */
		public final double vehicleDepartureTime;
		/** Time from the access stop departure to the egress stop arrival of the boarded route (s). */
		public final double inVehicleTime;
		public final boolean onPlannedRoute;
		public final boolean onPlannedLine;

		Boarding(TransitLine line, TransitRoute route, StopDeparture stopDeparture, double vehicleDepartureTime,
				double inVehicleTime, boolean onPlannedRoute, boolean onPlannedLine) {
			this.line = line;
			this.route = route;
			this.stopDeparture = stopDeparture;
			this.vehicleDepartureTime = vehicleDepartureTime;
			this.inVehicleTime = inVehicleTime;
			this.onPlannedRoute = onPlannedRoute;
			this.onPlannedLine = onPlannedLine;
		}

		double egressArrivalTime() {
			return vehicleDepartureTime + inVehicleTime;
		}
	}

	private static class LineRoute {
		final TransitLine line;
		final TransitRoute route;

		LineRoute(TransitLine line, TransitRoute route) {
			this.line = line;
			this.route = route;
		}
	}

	/**
	 * @param boardingToleranceSeconds how long (s, &gt;= 0) after a departure a
	 *                                 passenger may still board it; 1 s absorbs
	 *                                 the teleportation step of a zero-length
	 *                                 transfer walk, 0 restores the strict search
	 * @param lineFallbackEnabled      whether a passenger whose planned route has
	 *                                 no departure left may board another route of
	 *                                 the same line
	 */
	public TransitBoardingFinder(DepartureFinder departureFinder, double boardingToleranceSeconds,
			boolean lineFallbackEnabled) {
		this(departureFinder, boardingToleranceSeconds, lineFallbackEnabled, null, false);
	}

	/**
	 * @param schedule                the transit schedule; required when the
	 *                                stop-pair fallback is enabled
	 * @param stopPairFallbackEnabled whether a passenger who missed the planned
	 *                                departure boards the vehicle of any line
	 *                                serving the access and then the egress stop
	 *                                that arrives there first
	 */
	public TransitBoardingFinder(DepartureFinder departureFinder, double boardingToleranceSeconds,
			boolean lineFallbackEnabled, TransitSchedule schedule, boolean stopPairFallbackEnabled) {
		if (!(boardingToleranceSeconds >= 0.0) || Double.isInfinite(boardingToleranceSeconds)) {
			throw new IllegalArgumentException(
					"The transit boarding tolerance must be a finite number of seconds >= 0, got "
							+ boardingToleranceSeconds);
		}

		if (stopPairFallbackEnabled && schedule == null) {
			throw new IllegalArgumentException("The transit stop-pair fallback needs the transit schedule");
		}

		this.departureFinder = departureFinder;
		this.boardingToleranceSeconds = boardingToleranceSeconds;
		this.lineFallbackEnabled = lineFallbackEnabled;
		this.stopPairFallbackEnabled = stopPairFallbackEnabled;
		this.routesByStop = stopPairFallbackEnabled ? indexRoutesByStop(schedule) : Collections.emptyMap();
	}

	public double getBoardingToleranceSeconds() {
		return boardingToleranceSeconds;
	}

	public boolean isLineFallbackEnabled() {
		return lineFallbackEnabled;
	}

	public boolean isStopPairFallbackEnabled() {
		return stopPairFallbackEnabled;
	}

	/** Steps 1 and 2 only: the planned route, then (line fallback) the other routes of the planned line. */
	public Boarding findBoarding(TransitLine line, TransitRoute plannedRoute, Id<TransitStopFacility> accessStopId,
			Id<TransitStopFacility> egressStopId, double now) throws NoDepartureFoundException {
		double searchTime = now - boardingToleranceSeconds;

		try {
			return createBoarding(line, plannedRoute, accessStopId, egressStopId, searchTime, true, true);
		} catch (NoDepartureFoundException e) {
			if (!lineFallbackEnabled) {
				throw e;
			}
		}

		List<TransitRoute> otherRoutes = new ArrayList<>(line.getRoutes().values());
		otherRoutes.remove(plannedRoute);
		otherRoutes.sort(Comparator.comparing(route -> route.getId().toString()));

		Boarding best = null;

		for (TransitRoute route : otherRoutes) {
			if (servesInOrder(route, accessStopId, egressStopId)) {
				try {
					Boarding candidate = createBoarding(line, route, accessStopId, egressStopId, searchTime, false,
							true);

					if (best == null || candidate.vehicleDepartureTime < best.vehicleDepartureTime) {
						best = candidate;
					}
				} catch (NoDepartureFoundException e) {
					// this route has no departure left either
				}
			}
		}

		if (best == null) {
			throw new NoDepartureFoundException();
		}

		return best;
	}

	/**
	 * All three steps: as {@link #findBoarding(TransitLine, TransitRoute, Id, Id, double)}
	 * when the stop-pair fallback is off; with it on, a passenger whose planned
	 * route's next vehicle leaves after {@code plannedBoardingTime} (s), or which
	 * has none left, has missed the planned departure and boards the vehicle of any
	 * line that reaches the egress stop first.
	 */
	public Boarding findBoarding(TransitLine line, TransitRoute plannedRoute, Id<TransitStopFacility> accessStopId,
			Id<TransitStopFacility> egressStopId, double now, double plannedBoardingTime)
			throws NoDepartureFoundException {
		if (!stopPairFallbackEnabled) {
			return findBoarding(line, plannedRoute, accessStopId, egressStopId, now);
		}

		double searchTime = now - boardingToleranceSeconds;
		Boarding planned = null;

		try {
			planned = createBoarding(line, plannedRoute, accessStopId, egressStopId, searchTime, true, true);
		} catch (NoDepartureFoundException e) {
			// the planned route has no departure left
		}

		if (planned != null && planned.vehicleDepartureTime <= plannedBoardingTime) {
			// On time: the planned departure (or an earlier vehicle of the same route), unchanged behaviour
			return planned;
		}

		// Missed: the planned route first, then the planned line, then the others, each in lexical id order, so only
		// a strictly earlier arrival at the egress stop makes the passenger leave the planned route or line
		List<LineRoute> candidates = new ArrayList<>(routesByStop.getOrDefault(accessStopId, Collections.emptyList()));
		candidates.sort(Comparator.<LineRoute>comparingInt(candidate -> candidate.line == line ? 0 : 1)
				.thenComparing(candidate -> candidate.line.getId().toString())
				.thenComparing(candidate -> candidate.route.getId().toString()));

		Boarding best = planned;

		for (LineRoute candidate : candidates) {
			if (candidate.route != plannedRoute && servesInOrder(candidate.route, accessStopId, egressStopId)) {
				try {
					Boarding boarding = createBoarding(candidate.line, candidate.route, accessStopId, egressStopId,
							searchTime, false, candidate.line == line);

					if (best == null || boarding.egressArrivalTime() < best.egressArrivalTime()) {
						best = boarding;
					}
				} catch (NoDepartureFoundException e) {
					// this route has no departure left
				}
			}
		}

		if (best == null) {
			throw new NoDepartureFoundException();
		}

		return best;
	}

	private Boarding createBoarding(TransitLine line, TransitRoute route, Id<TransitStopFacility> accessStopId,
			Id<TransitStopFacility> egressStopId, double searchTime, boolean onPlannedRoute, boolean onPlannedLine)
			throws NoDepartureFoundException {
		StopDeparture stopDeparture = departureFinder.findNextDeparture(route, accessStopId, egressStopId, searchTime);
		double accessDepartureOffset = stopDeparture.stop.getDepartureOffset().seconds();
		double vehicleDepartureTime = stopDeparture.departure.getDepartureTime() + accessDepartureOffset;
		double inVehicleTime = egressArrivalOffset(route, stopDeparture.stop, egressStopId) - accessDepartureOffset;
		return new Boarding(line, route, stopDeparture, vehicleDepartureTime, inVehicleTime, onPlannedRoute,
				onPlannedLine);
	}

	private static Map<Id<TransitStopFacility>, List<LineRoute>> indexRoutesByStop(TransitSchedule schedule) {
		Map<Id<TransitStopFacility>, List<LineRoute>> index = new HashMap<>();

		for (TransitLine line : schedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				for (TransitRouteStop stop : route.getStops()) {
					List<LineRoute> routes = index.computeIfAbsent(stop.getStopFacility().getId(),
							id -> new ArrayList<>());

					// a route that serves a stop more than once (a loop) is listed once
					if (routes.isEmpty() || routes.get(routes.size() - 1).route != route) {
						routes.add(new LineRoute(line, route));
					}
				}
			}
		}

		return index;
	}

	/** Whether the route serves the access stop and, at a later stop, the egress stop. */
	static boolean servesInOrder(TransitRoute route, Id<TransitStopFacility> accessStopId,
			Id<TransitStopFacility> egressStopId) {
		boolean accessSeen = false;

		for (TransitRouteStop stop : route.getStops()) {
			Id<TransitStopFacility> facilityId = stop.getStopFacility().getId();

			if (accessSeen && facilityId.equals(egressStopId)) {
				return true;
			}

			if (facilityId.equals(accessStopId)) {
				accessSeen = true;
			}
		}

		return false;
	}

	/** Arrival offset of the first egress stop after the given access stop (departure offset if no arrival). */
	private static double egressArrivalOffset(TransitRoute route, TransitRouteStop accessStop,
			Id<TransitStopFacility> egressStopId) {
		List<TransitRouteStop> stops = route.getStops();

		for (int index = stops.indexOf(accessStop) + 1; index < stops.size(); index++) {
			TransitRouteStop stop = stops.get(index);

			if (stop.getStopFacility().getId().equals(egressStopId)) {
				return stop.getArrivalOffset().isDefined() ? stop.getArrivalOffset().seconds()
						: stop.getDepartureOffset().seconds();
			}
		}

		throw new IllegalStateException("Egress stop " + egressStopId + " not found after the access stop on route "
				+ route.getId());
	}
}
