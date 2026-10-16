package org.eqasim.braunschweig.scenario;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.utils.collections.QuadTree;
import org.matsim.core.utils.geometry.CoordUtils;

/**
 * Chooses the network link on which a boundary-trimmed freight trip starts or ends (issue #444).
 *
 * <p>
 * The freight extraction cuts every incoming, outgoing and transit route where it crosses the study
 * area and keeps only the crossing coordinate. Left without a link, such an activity is snapped by
 * MATSim's {@code XY2Links}, which takes the nearest network NODE and then one of its links. Motorway
 * nodes are hundreds of metres apart while residential nodes are dense, so a crossing point lying on
 * the A2 was snapped onto a 600 veh/h residential street next to it, and every truck entering there
 * queued on that street. This class replaces that rule for boundary endpoints:
 * </p>
 * <ul>
 * <li>only car links with at least a configured capacity (veh/h) are candidates: a long-distance truck
 * crosses the cordon on a major road, and a low-capacity street cannot carry the boundary flow. On the
 * Braunschweig network 1000 veh/h admits exactly the motorway, trunk and primary classes (measured
 * with {@code scripts/measure_freight_boundary_links.py} in eqasim-bs);</li>
 * <li>distance is the perpendicular distance from the point to the link segment, not to a node;</li>
 * <li>only links whose heading agrees with the trip direction qualify: an entry link points from the
 * boundary point towards the trip's other end, an exit link points away from it. This picks the
 * correct carriageway of a dual carriageway even when the opposite one is closer;</li>
 * <li>among qualifying links within {@code maximumDistanceMeters} the closest wins, ties go to the
 * lower link id (deterministic).</li>
 * </ul>
 *
 * <p>
 * If no link qualifies, the selector returns empty and the caller leaves the activity to MATSim's
 * snapping; the caller counts these fallbacks and fails via {@link #checkFallbackShare} when they
 * exceed a configured share. Assumes a metric CRS (distances in metres); capacities are converted to
 * vehicles per hour with the network's capacity period. No side effects.
 * </p>
 */
public final class FreightBoundaryLinkSelector {
	private final QuadTree<Link> candidatesByMidpoint;
	private final int candidateLinkCount;
	private final double maximumDistanceMeters;
	private final double midpointSearchRadiusMeters;

	/**
	 * @param network                          the (cut) scenario network
	 * @param minimumCapacityVehiclesPerHour   smallest link capacity (veh/h) that may carry a boundary endpoint;
	 *                                         must be non-negative
	 * @param maximumDistanceMeters            largest accepted point-to-link distance in metres; must be positive
	 */
	public FreightBoundaryLinkSelector(Network network, double minimumCapacityVehiclesPerHour,
			double maximumDistanceMeters) {
		if (!(minimumCapacityVehiclesPerHour >= 0.0) || Double.isInfinite(minimumCapacityVehiclesPerHour)) {
			throw new IllegalArgumentException("minimumCapacityVehiclesPerHour must be a non-negative finite number, got "
					+ minimumCapacityVehiclesPerHour);
		}
		if (!(maximumDistanceMeters > 0.0) || Double.isInfinite(maximumDistanceMeters)) {
			throw new IllegalArgumentException(
					"maximumDistanceMeters must be a positive finite number, got " + maximumDistanceMeters);
		}
		this.maximumDistanceMeters = maximumDistanceMeters;

		List<Link> candidates = new ArrayList<>();
		double maximumHalfLengthMeters = 0.0;
		double minX = Double.POSITIVE_INFINITY;
		double minY = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY;
		double maxY = Double.NEGATIVE_INFINITY;
		double vehiclesPerHourPerCapacityUnit = 3600.0 / network.getCapacityPeriod();
		for (Link link : network.getLinks().values()) {
			if (!link.getAllowedModes().contains(TransportMode.car)
					|| link.getCapacity() * vehiclesPerHourPerCapacityUnit < minimumCapacityVehiclesPerHour) {
				continue;
			}
			candidates.add(link);
			Coord from = link.getFromNode().getCoord();
			Coord to = link.getToNode().getCoord();
			maximumHalfLengthMeters = Math.max(maximumHalfLengthMeters, CoordUtils.calcEuclideanDistance(from, to) / 2.0);
			minX = Math.min(minX, Math.min(from.getX(), to.getX()));
			minY = Math.min(minY, Math.min(from.getY(), to.getY()));
			maxX = Math.max(maxX, Math.max(from.getX(), to.getX()));
			maxY = Math.max(maxY, Math.max(from.getY(), to.getY()));
		}
		this.candidateLinkCount = candidates.size();
		// Links are indexed by midpoint. A segment within maximumDistanceMeters of a point has its midpoint
		// within maximumDistanceMeters + half its length, so this radius never misses a qualifying link.
		this.midpointSearchRadiusMeters = maximumDistanceMeters + maximumHalfLengthMeters;
		if (candidates.isEmpty()) {
			this.candidatesByMidpoint = null;
		} else {
			this.candidatesByMidpoint = new QuadTree<>(minX, minY, maxX, maxY);
			for (Link link : candidates) {
				Coord midpoint = link.getCoord();
				candidatesByMidpoint.put(midpoint.getX(), midpoint.getY(), link);
			}
		}
	}

	/** Number of links eligible as boundary links (car mode and at least the minimum capacity). */
	public int getCandidateLinkCount() {
		return candidateLinkCount;
	}

	/**
	 * Link on which a trip that enters the network at {@code boundaryPoint} starts.
	 *
	 * @param boundaryPoint the trimmed start coordinate
	 * @param otherTripEnd  the trip's end coordinate; gives the travel direction
	 */
	public Optional<Link> selectEntryLink(Coord boundaryPoint, Coord otherTripEnd) {
		return select(boundaryPoint, otherTripEnd.getX() - boundaryPoint.getX(),
				otherTripEnd.getY() - boundaryPoint.getY());
	}

	/**
	 * Link on which a trip that leaves the network at {@code boundaryPoint} ends.
	 *
	 * @param boundaryPoint the trimmed end coordinate
	 * @param otherTripEnd  the trip's start coordinate; gives the travel direction
	 */
	public Optional<Link> selectExitLink(Coord boundaryPoint, Coord otherTripEnd) {
		return select(boundaryPoint, boundaryPoint.getX() - otherTripEnd.getX(),
				boundaryPoint.getY() - otherTripEnd.getY());
	}

	private Optional<Link> select(Coord point, double directionX, double directionY) {
		if (candidatesByMidpoint == null) {
			return Optional.empty();
		}
		Collection<Link> nearby = candidatesByMidpoint.getDisk(point.getX(), point.getY(), midpointSearchRadiusMeters);
		Link best = null;
		double bestDistanceMeters = Double.POSITIVE_INFINITY;
		for (Link link : nearby) {
			Coord from = link.getFromNode().getCoord();
			Coord to = link.getToNode().getCoord();
			double heading = (to.getX() - from.getX()) * directionX + (to.getY() - from.getY()) * directionY;
			if (heading <= 0.0) {
				continue;
			}
			double distanceMeters = CoordUtils.distancePointLinesegment(from, to, point);
			if (distanceMeters > maximumDistanceMeters) {
				continue;
			}
			if (distanceMeters < bestDistanceMeters || (distanceMeters == bestDistanceMeters
					&& link.getId().toString().compareTo(best.getId().toString()) < 0)) {
				best = link;
				bestDistanceMeters = distanceMeters;
			}
		}
		return Optional.ofNullable(best);
	}

	/**
	 * Fails when too many boundary endpoints fell back to MATSim's nearest-node snapping: a high share
	 * means the rule itself is broken (wrong CRS, a too high minimum capacity, a too small search distance).
	 *
	 * @param assignedCount        endpoints that got a link from this selector
	 * @param fallbackCount        endpoints left to MATSim's snapping
	 * @param maximumFallbackShare largest tolerated fallback share in [0, 1]
	 * @throws IllegalStateException if the fallback share exceeds {@code maximumFallbackShare}
	 */
	public static void checkFallbackShare(int assignedCount, int fallbackCount, double maximumFallbackShare) {
		if (!(maximumFallbackShare >= 0.0 && maximumFallbackShare <= 1.0)) {
			throw new IllegalArgumentException("maximumFallbackShare must lie in [0, 1], got " + maximumFallbackShare);
		}
		int total = assignedCount + fallbackCount;
		if (total == 0) {
			return;
		}
		double share = (double) fallbackCount / total;
		if (share > maximumFallbackShare) {
			throw new IllegalStateException(String.format(
					"freight boundary links: %d of %d boundary endpoints (%.1f %%) found no link with the minimum capacity"
							+ " heading in the trip direction and would fall back to nearest-node snapping, above the allowed"
							+ " %.1f %%. Check the network CRS, the minimum capacity and the maximum distance.",
					fallbackCount, total, 100.0 * share, 100.0 * maximumFallbackShare));
		}
	}
}
