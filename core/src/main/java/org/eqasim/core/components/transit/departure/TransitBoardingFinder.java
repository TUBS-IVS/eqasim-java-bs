package org.eqasim.core.components.transit.departure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eqasim.core.components.transit.departure.DepartureFinder.NoDepartureFoundException;
import org.eqasim.core.components.transit.departure.DepartureFinder.StopDeparture;
import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
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
 * </ol>
 *
 * Throws {@link NoDepartureFoundException} when neither exists; the engine then
 * aborts the agent as before.
 */
public class TransitBoardingFinder {
	private final DepartureFinder departureFinder;
	private final double boardingToleranceSeconds;
	private final boolean lineFallbackEnabled;

	/** The departure a passenger boards. */
	static public class Boarding {
		public final TransitRoute route;
		public final StopDeparture stopDeparture;
		/** Departure time of the vehicle at the access stop (s). */
		public final double vehicleDepartureTime;
		/** Time from the access stop departure to the egress stop arrival of the boarded route (s). */
		public final double inVehicleTime;
		public final boolean onPlannedRoute;

		Boarding(TransitRoute route, StopDeparture stopDeparture, double vehicleDepartureTime, double inVehicleTime,
				boolean onPlannedRoute) {
			this.route = route;
			this.stopDeparture = stopDeparture;
			this.vehicleDepartureTime = vehicleDepartureTime;
			this.inVehicleTime = inVehicleTime;
			this.onPlannedRoute = onPlannedRoute;
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
		if (!(boardingToleranceSeconds >= 0.0) || Double.isInfinite(boardingToleranceSeconds)) {
			throw new IllegalArgumentException(
					"The transit boarding tolerance must be a finite number of seconds >= 0, got "
							+ boardingToleranceSeconds);
		}

		this.departureFinder = departureFinder;
		this.boardingToleranceSeconds = boardingToleranceSeconds;
		this.lineFallbackEnabled = lineFallbackEnabled;
	}

	public double getBoardingToleranceSeconds() {
		return boardingToleranceSeconds;
	}

	public boolean isLineFallbackEnabled() {
		return lineFallbackEnabled;
	}

	public Boarding findBoarding(TransitLine line, TransitRoute plannedRoute, Id<TransitStopFacility> accessStopId,
			Id<TransitStopFacility> egressStopId, double now) throws NoDepartureFoundException {
		double searchTime = now - boardingToleranceSeconds;

		try {
			return createBoarding(plannedRoute, accessStopId, egressStopId, searchTime, true);
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
					Boarding candidate = createBoarding(route, accessStopId, egressStopId, searchTime, false);

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

	private Boarding createBoarding(TransitRoute route, Id<TransitStopFacility> accessStopId,
			Id<TransitStopFacility> egressStopId, double searchTime, boolean onPlannedRoute)
			throws NoDepartureFoundException {
		StopDeparture stopDeparture = departureFinder.findNextDeparture(route, accessStopId, egressStopId, searchTime);
		double accessDepartureOffset = stopDeparture.stop.getDepartureOffset().seconds();
		double vehicleDepartureTime = stopDeparture.departure.getDepartureTime() + accessDepartureOffset;
		double inVehicleTime = egressArrivalOffset(route, stopDeparture.stop, egressStopId) - accessDepartureOffset;
		return new Boarding(route, stopDeparture, vehicleDepartureTime, inVehicleTime, onPlannedRoute);
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
