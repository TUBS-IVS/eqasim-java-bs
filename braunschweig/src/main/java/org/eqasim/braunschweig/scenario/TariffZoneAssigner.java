package org.eqasim.braunschweig.scenario;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.matsim.api.core.v01.Coord;

/**
 * Point-in-polygon assignment of a VRB tariff zone (ADR-0133 D2). A point exactly on a shared border
 * is covered by two polygons; the lower numeric zone id wins deterministically and the case is
 * flagged as a tie. Coordinates must be in the polygons' CRS (EPSG:25832).
 */
public final class TariffZoneAssigner {
	public record Zone(String zoneId, Geometry geometry) {
	}

	public record Assignment(String zoneId, boolean tie) {
	}

	private static final GeometryFactory FACTORY = new GeometryFactory();
	private final List<Zone> zones;
	private final List<PreparedGeometry> prepared;

	public TariffZoneAssigner(List<Zone> zones) {
		Set<String> ids = new HashSet<>();
		List<Zone> ordered = new ArrayList<>(zones);
		ordered.sort(Comparator.comparingInt(zone -> Integer.parseInt(zone.zoneId())));
		for (Zone zone : ordered) {
			if (!ids.add(zone.zoneId())) {
				throw new IllegalArgumentException("duplicate tariff zone id " + zone.zoneId());
			}
		}
		this.zones = List.copyOf(ordered);
		this.prepared = this.zones.stream().map(zone -> PreparedGeometryFactory.prepare(zone.geometry())).toList();
	}

	/** The zone covering the coordinate (lowest id on a tie), or a null zone id outside every polygon. */
	public Assignment assign(Coord coord) {
		Point point = FACTORY.createPoint(new Coordinate(coord.getX(), coord.getY()));
		String first = null;
		int covering = 0;
		for (int index = 0; index < zones.size(); index++) {
			if (prepared.get(index).covers(point)) {
				covering++;
				if (first == null) {
					first = zones.get(index).zoneId();
				}
			}
		}
		return new Assignment(first, covering > 1);
	}
}
