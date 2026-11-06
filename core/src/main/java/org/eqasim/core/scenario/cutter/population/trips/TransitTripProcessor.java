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

	// Shared by the threads of the population cutter (the processor is a singleton in the kept-stops mode)
	final private Map<String, AtomicLong> collapseCounts = new LinkedHashMap<>();

	public TransitTripProcessor(TransitTripCrossingPointFinder transitPointFinder, ScenarioExtent extent,
			double departureTimeBuffer) {
		this(transitPointFinder, extent, departureTimeBuffer, false);
	}

	/**
	 * @param collapseExcursions whether to collapse the crossing points of a trip to at most one per outside end
	 *                           (kept-stops mode, eqasim-bs#574, design R5); false keeps one outside activity per
	 *                           crossing point (extent mode, upstream behaviour)
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
		return process(firstActivity.getCoord(), trip, secondActivity.getCoord(),
				!extent.isInside(firstActivity.getCoord()) && !extent.isInside(secondActivity.getCoord()), routingMode);
	}

	public List<PlanElement> process(Coord firstCoord, List<PlanElement> trip, Coord secondCoord, boolean allOutside,
			String routingMode) {
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
					result.add(activity);
				} else {
					Activity activity = PopulationUtils.createActivityFromCoord("outside",
							point.teleportationPoint.coord);
					activity.setEndTime(point.teleportationPoint.time);
					result.add(activity);
				}

				result.add(PopulationUtils.createLeg(point.isOutgoing ? "outside" : routingMode));
			}

			return result;
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
	 * point after it (none if there is no such pair, which leaves the trip an outside leg). A list without the point
	 * a case needs (not produced by a consistent finder) is returned unchanged.
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
			return lastIncoming < 0 ? points : List.of(points.get(lastIncoming));
		} else if (originInside) {
			return firstOutgoing < 0 ? points : List.of(points.get(firstOutgoing));
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
}
