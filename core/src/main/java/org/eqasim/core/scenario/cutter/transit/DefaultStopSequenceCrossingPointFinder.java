package org.eqasim.core.scenario.cutter.transit;

import java.util.LinkedList;
import java.util.List;

import org.eqasim.core.scenario.cutter.extent.ScenarioExtent;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;

/**
 * @deprecated The {@link TransitScheduleCutter} no longer uses crossing points: since eqasim-bs#574 it keeps
 *             the stops between the first and the last anchor (inside the extent or hub). Kept for parity
 *             with upstream eqasim.
 */
@Deprecated
public class DefaultStopSequenceCrossingPointFinder implements StopSequenceCrossingPointFinder {
	private final ScenarioExtent extent;

	public DefaultStopSequenceCrossingPointFinder(ScenarioExtent extent) {
		this.extent = extent;
	}

	@Override
	public List<StopSequenceCrossingPoint> findCrossingPoints(List<TransitRouteStop> stopSequence) {
		List<StopSequenceCrossingPoint> crossingPoints = new LinkedList<>();

		for (int i = 0; i < stopSequence.size() - 1; i++) {
			TransitRouteStop firstStop = stopSequence.get(i);
			TransitRouteStop secondStop = stopSequence.get(i + 1);

			boolean firstIsInside = extent.isInside(firstStop.getStopFacility().getCoord());
			boolean secondIsInside = extent.isInside(secondStop.getStopFacility().getCoord());

			if (firstIsInside != secondIsInside) { // We found a crossing
				TransitRouteStop insideStop = firstIsInside ? firstStop : secondStop;
				TransitRouteStop outsideStop = firstIsInside ? secondStop : firstStop;

				crossingPoints.add(new StopSequenceCrossingPoint(insideStop, outsideStop, firstIsInside, i));
			}
		}

		return crossingPoints;
	}
}
