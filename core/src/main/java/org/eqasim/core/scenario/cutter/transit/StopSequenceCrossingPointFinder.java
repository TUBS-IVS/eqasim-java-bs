package org.eqasim.core.scenario.cutter.transit;

import java.util.List;

import org.matsim.pt.transitSchedule.api.TransitRouteStop;

/**
 * @deprecated The {@link TransitScheduleCutter} no longer uses crossing points: since eqasim-bs#574 it keeps
 *             the stops between the first and the last anchor (inside the extent or hub). Kept for parity
 *             with upstream eqasim.
 */
@Deprecated
public interface StopSequenceCrossingPointFinder {
	List<StopSequenceCrossingPoint> findCrossingPoints(List<TransitRouteStop> stopSequence);
}
