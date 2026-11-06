package org.eqasim.core.scenario.cutter.population.trips;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.eqasim.core.misc.Constants;
import org.eqasim.core.scenario.cutter.extent.ScenarioExtent;
import org.eqasim.core.scenario.cutter.population.trips.crossing.transit.TransitTripCrossingPoint;
import org.eqasim.core.scenario.cutter.population.trips.crossing.transit.TransitTripCrossingPointFinder;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.population.PopulationUtils;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

public class TransitTripProcessor implements TripProcessor {
	/** Key of {@link #getCollapseCounts()}: trips with at least one crossing point (collapse mode only). */
	public static final String TRIPS_WITH_CROSSINGS = "trips_with_crossings";
	public static final String INSIDE_INSIDE = "inside_inside";
	public static final String OUTSIDE_ORIGIN = "outside_origin";
	public static final String OUTSIDE_DESTINATION = "outside_destination";
	public static final String THROUGH = "through";

	/** The collapse cases, in the order of {@link #getCollapseCounts()} after {@link #TRIPS_WITH_CROSSINGS}. */
	public static final List<String> COLLAPSE_CASES = List.of(INSIDE_INSIDE, OUTSIDE_ORIGIN, OUTSIDE_DESTINATION,
			THROUGH);

	final private TransitTripCrossingPointFinder transitPointFinder;
	final private double departureTimeBuffer;
	final private ScenarioExtent extent;
	final private boolean collapseExcursions;

	// Shared by the threads of the population cutter (the processor is a singleton)
	final private Map<String, AtomicLong> collapseCounts = new LinkedHashMap<>();
	final private AtomicLong processedTrips = new AtomicLong();

	/** Thrown by {@link #collapse} for a point list that no consistent finder produces. */
	static class InconsistentCrossingPointsException extends IllegalStateException {
		private static final long serialVersionUID = 1L;

		InconsistentCrossingPointsException(String message) {
			super(message);
		}
	}

	public TransitTripProcessor(TransitTripCrossingPointFinder transitPointFinder, ScenarioExtent extent,
			double departureTimeBuffer) {
		this(transitPointFinder, extent, departureTimeBuffer, false);
	}

	/**
	 * @param collapseExcursions whether to collapse the crossing points of a trip to at most one per outside end
	 *                           (kept-stops mode, eqasim-bs#574, design R5) and to mark every outside activity built
	 *                           at a stop (an in-vehicle point, or a walk point that carries a stop facility id) with
	 *                           the attribute {@link Constants#CUT_TRANSIT_STOP_ATTRIBUTE} (the stop facility id), so
	 *                           that the outside activity adapter anchors it at the stop; false keeps one outside
	 *                           activity per crossing point and never sets the attribute (extent mode, upstream
	 *                           behaviour)
	 */
	public TransitTripProcessor(TransitTripCrossingPointFinder transitPointFinder, ScenarioExtent extent,
			double departureTimeBuffer, boolean collapseExcursions) {
		this.transitPointFinder = transitPointFinder;
		this.departureTimeBuffer = departureTimeBuffer;
		this.extent = extent;
		this.collapseExcursions = collapseExcursions;

		collapseCounts.put(TRIPS_WITH_CROSSINGS, new AtomicLong());

		for (String collapseCase : COLLAPSE_CASES) {
			collapseCounts.put(collapseCase, new AtomicLong());
		}
	}

	@Override
	public List<PlanElement> process(Id<Person> personId, int firstLegIndex, Activity firstActivity,
			List<PlanElement> trip, Activity secondActivity, String routingMode) {
		try {
			return process(firstActivity.getCoord(), trip, secondActivity.getCoord(),
					!extent.isInside(firstActivity.getCoord()) && !extent.isInside(secondActivity.getCoord()),
					routingMode);
		} catch (InconsistentCrossingPointsException e) {
			throw new IllegalStateException(
					"Person " + personId + ", trip from leg index " + firstLegIndex + ": " + e.getMessage(), e);
		}
	}

	public List<PlanElement> process(Coord firstCoord, List<PlanElement> trip, Coord secondCoord, boolean allOutside,
			String routingMode) {
		processedTrips.incrementAndGet();

		List<TransitTripCrossingPoint> crossingPoints = transitPointFinder.findCrossingPoints(firstCoord, trip,
				secondCoord);

		String collapseCase = null;

		if (collapseExcursions && crossingPoints.size() > 0) {
			collapseCounts.get(TRIPS_WITH_CROSSINGS).incrementAndGet();

			boolean originInside = extent.isInside(firstCoord);
			boolean destinationInside = extent.isInside(secondCoord);
			List<TransitTripCrossingPoint> collapsed = collapse(crossingPoints, originInside, destinationInside);

			if (collapsed.size() < crossingPoints.size()) {
				collapseCase = collapseCase(originInside, destinationInside);

				// A through trip that never enters stays an outside leg: nothing is simulated, so not counted
				if (!(collapseCase.equals(THROUGH) && collapsed.isEmpty())) {
					collapseCounts.get(collapseCase).incrementAndGet();
				}
			}

			crossingPoints = collapsed;
		}

		List<PlanElement> result = buildElements(crossingPoints, allOutside, routingMode);

		if (collapseCase != null) {
			for (PlanElement element : result) {
				if (element instanceof Leg leg && leg.getMode().equals(routingMode)) {
					leg.getAttributes().putAttribute(Constants.CUT_TRANSIT_COLLAPSE_ATTRIBUTE, collapseCase);
				}
			}
		}

		return result;
	}

	private List<PlanElement> buildElements(List<TransitTripCrossingPoint> crossingPoints, boolean allOutside,
			String routingMode) {
		if (crossingPoints.size() == 0) {
			return Arrays.asList(PopulationUtils.createLeg(allOutside ? "outside" : routingMode));
		} else {
			List<PlanElement> result = new LinkedList<>();

			result.add(PopulationUtils.createLeg(crossingPoints.get(0).isOutgoing ? routingMode : "outside"));

			for (TransitTripCrossingPoint point : crossingPoints) {
				if (point.isInVehicle) {
					Activity activity = PopulationUtils.createActivityFromCoord("outside",
							point.transitRoutePoint.insideStop.getStopFacility().getCoord());
					activity.setEndTime(point.transitRoutePoint.insideDepartureTime - departureTimeBuffer);
					markStop(activity, point.transitRoutePoint.insideStop.getStopFacility().getId());
					result.add(activity);
				} else {
					Activity activity = PopulationUtils.createActivityFromCoord("outside",
							point.teleportationPoint.coord);
					activity.setEndTime(point.teleportationPoint.time);
					markStop(activity, point.teleportationPoint.stopFacilityId);
					result.add(activity);
				}

				result.add(PopulationUtils.createLeg(point.isOutgoing ? "outside" : routingMode));
			}

			return result;
		}
	}

	/**
	 * Puts the stop attribute on an outside activity built at a stop, in the kept-stops mode only; a point without a
	 * stop (a crossing of the extent polygon) and the extent mode leave the activity untouched.
	 */
	private void markStop(Activity activity, Id<TransitStopFacility> stopId) {
		if (collapseExcursions && stopId != null) {
			activity.getAttributes().putAttribute(Constants.CUT_TRANSIT_STOP_ATTRIBUTE, stopId.toString());
		}
	}

	private static String collapseCase(boolean originInside, boolean destinationInside) {
		if (originInside && destinationInside) {
			return INSIDE_INSIDE;
		} else if (destinationInside) {
			return OUTSIDE_ORIGIN;
		} else if (originInside) {
			return OUTSIDE_DESTINATION;
		} else {
			return THROUGH;
		}
	}

	/**
	 * Keeps at most one crossing point per outside end of the trip (design R5): both ends inside the extent: none
	 * (the trip is one leg of the routing mode, re-routed on the cut timetable); origin outside: the LAST incoming
	 * point; destination outside: the FIRST outgoing point; both outside: the first incoming and the last outgoing
	 * point after it (none if there is no such pair, which leaves the trip an outside leg). A non-empty list without
	 * the point that a case with one end inside needs cannot come from a consistent finder (the points alternate,
	 * starting with outgoing iff the origin is inside) and fails with an IllegalStateException.
	 */
	static List<TransitTripCrossingPoint> collapse(List<TransitTripCrossingPoint> points, boolean originInside,
			boolean destinationInside) {
		if (originInside && destinationInside) {
			return Collections.emptyList();
		}

		int firstIncoming = -1;
		int lastIncoming = -1;
		int firstOutgoing = -1;
		int lastOutgoing = -1;

		for (int i = 0; i < points.size(); i++) {
			if (points.get(i).isOutgoing) {
				firstOutgoing = firstOutgoing < 0 ? i : firstOutgoing;
				lastOutgoing = i;
			} else {
				firstIncoming = firstIncoming < 0 ? i : firstIncoming;
				lastIncoming = i;
			}
		}

		if (destinationInside) {
			if (lastIncoming < 0) {
				throw new InconsistentCrossingPointsException("Inconsistent crossing points of a pt trip ("
						+ OUTSIDE_ORIGIN + "): the destination is inside, but none of the " + points.size()
						+ " points enters");
			}

			return List.of(points.get(lastIncoming));
		} else if (originInside) {
			if (firstOutgoing < 0) {
				throw new InconsistentCrossingPointsException("Inconsistent crossing points of a pt trip ("
						+ OUTSIDE_DESTINATION + "): the origin is inside, but none of the " + points.size()
						+ " points leaves");
			}

			return List.of(points.get(firstOutgoing));
		} else if (firstIncoming < 0 || lastOutgoing < firstIncoming) {
			return Collections.emptyList();
		} else {
			return List.of(points.get(firstIncoming), points.get(lastOutgoing));
		}
	}

	/**
	 * The collapse counts so far, in a fixed order: {@code trips_with_crossings} (trips for which the finder reported
	 * at least one crossing point), then per case the trips whose crossing points were shortened and that still
	 * emit a leg of the routing mode. Always zero when the processor does not collapse.
	 */
	public Map<String, Long> getCollapseCounts() {
		Map<String, Long> counts = new LinkedHashMap<>();
		collapseCounts.forEach((key, value) -> counts.put(key, value.get()));
		return counts;
	}

	/** The pt trips processed so far (with or without crossing points), the denominator of the collapse counts. */
	public long getProcessedTrips() {
		return processedTrips.get();
	}

	/** Whether this processor collapses excursions (kept-stops mode). */
	public boolean isCollapseExcursions() {
		return collapseExcursions;
	}
}
