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
 * Chooses the access link of a large facility (issue #465, ADR-0169).
 *
 * <p>
 * eqasim's {@code FacilityPlacement} attaches every facility to its single nearest car link that is not a
 * motorway, trunk or ramp, whatever that link's capacity. All car departures of the facility then enter the
 * network on that one link. For a large site this is a 600 veh/h side street in many cases (the VW Wolfsburg
 * plant location carries 34 303 activities on one tertiary link in the 100 % run), and the departures alone
 * saturate its outflow. This class gives such a facility a link that can carry the flow:
 * </p>
 * <ul>
 * <li>candidates carry {@code car}, have at least a configured capacity (veh/h, converted with the network's
 * capacity period) and pass the same class filter as eqasim's {@code OSMFacilityPlacementVoter}: no
 * {@code motorway}, {@code trunk} or {@code *_link} class (a site exit onto a motorway is not plausible);
 * links without the OSM highway attribute are eligible, as in the voter;</li>
 * <li>distance is the perpendicular distance from the facility coordinate to the link segment;</li>
 * <li>among candidates within {@code maximumDistanceMeters} the closest wins, ties go to the lower link id
 * (deterministic).</li>
 * </ul>
 *
 * <p>
 * Both directions of a two-way road are separate links at the same distance; the tie rule picks one of them.
 * The facility keeps one access link, as MATSim allows only one per facility. Assumes a metric CRS (distances
 * in metres). No side effects. The caller decides which facilities are large and counts the facilities for
 * which {@link #select} returns empty (fallback to the current link).
 * </p>
 */
public final class LargeFacilityAccessLinkSelector {
	/** OSM highway class attribute written by pt2matsim; the same key eqasim's placement voter reads. */
	public static final String HIGHWAY_ATTRIBUTE = "osm:way:highway";

	private final QuadTree<Link> candidatesByMidpoint;
	private final int candidateLinkCount;
	private final double minimumCapacityVehiclesPerHour;
	private final double vehiclesPerHourPerCapacityUnit;
	private final double maximumDistanceMeters;
	private final double midpointSearchRadiusMeters;

	/**
	 * @param network                        the prepared scenario network
	 * @param minimumCapacityVehiclesPerHour smallest capacity (veh/h) of an access link; non-negative and finite
	 * @param maximumDistanceMeters          largest facility-to-link distance in metres; positive and finite
	 */
	public LargeFacilityAccessLinkSelector(Network network, double minimumCapacityVehiclesPerHour,
			double maximumDistanceMeters) {
		if (!(minimumCapacityVehiclesPerHour >= 0.0) || Double.isInfinite(minimumCapacityVehiclesPerHour)) {
			throw new IllegalArgumentException("minimumCapacityVehiclesPerHour must be a non-negative finite number, got "
					+ minimumCapacityVehiclesPerHour);
		}
		if (!(maximumDistanceMeters > 0.0) || Double.isInfinite(maximumDistanceMeters)) {
			throw new IllegalArgumentException(
					"maximumDistanceMeters must be a positive finite number, got " + maximumDistanceMeters);
		}
		this.minimumCapacityVehiclesPerHour = minimumCapacityVehiclesPerHour;
		this.vehiclesPerHourPerCapacityUnit = 3600.0 / network.getCapacityPeriod();
		this.maximumDistanceMeters = maximumDistanceMeters;

		List<Link> candidates = new ArrayList<>();
		double maximumHalfLengthMeters = 0.0;
		double minX = Double.POSITIVE_INFINITY;
		double minY = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY;
		double maxY = Double.NEGATIVE_INFINITY;
		for (Link link : network.getLinks().values()) {
			if (!link.getAllowedModes().contains(TransportMode.car) || !hasMinimumCapacity(link)
					|| !isPlacementClass(link)) {
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

	/** Same class filter as eqasim's {@code OSMFacilityPlacementVoter}. */
	private static boolean isPlacementClass(Link link) {
		Object highway = link.getAttributes().getAttribute(HIGHWAY_ATTRIBUTE);
		if (highway == null) {
			return true;
		}
		String value = highway.toString();
		return !(value.contains("motorway") || value.contains("trunk") || value.contains("_link"));
	}

	/** Whether {@code link} has at least the minimum capacity (veh/h); a facility on such a link keeps it. */
	public boolean hasMinimumCapacity(Link link) {
		return link.getCapacity() * vehiclesPerHourPerCapacityUnit >= minimumCapacityVehiclesPerHour;
	}

	/** Capacity of {@code link} in vehicles per hour. */
	public double capacityVehiclesPerHour(Link link) {
		return link.getCapacity() * vehiclesPerHourPerCapacityUnit;
	}

	/** Number of eligible access links in the network. */
	public int getCandidateLinkCount() {
		return candidateLinkCount;
	}

	/** Closest eligible link within the maximum distance of {@code facilityCoord}, or empty if there is none. */
	public Optional<Link> select(Coord facilityCoord) {
		if (candidatesByMidpoint == null) {
			return Optional.empty();
		}
		Collection<Link> nearby = candidatesByMidpoint.getDisk(facilityCoord.getX(), facilityCoord.getY(),
				midpointSearchRadiusMeters);
		Link best = null;
		double bestDistanceMeters = Double.POSITIVE_INFINITY;
		for (Link link : nearby) {
			double distanceMeters = distanceMeters(link, facilityCoord);
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

	/** Perpendicular distance in metres from {@code coord} to the segment of {@code link}. */
	public static double distanceMeters(Link link, Coord coord) {
		return CoordUtils.distancePointLinesegment(link.getFromNode().getCoord(), link.getToNode().getCoord(), coord);
	}
}
