package org.eqasim.braunschweig.scenario;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.matsim.api.core.v01.Coord;

public class TariffZoneAssignerTest {
	private static final GeometryFactory FACTORY = new GeometryFactory();

	private static Geometry square(double x0, double y0, double x1, double y1) {
		return FACTORY.createPolygon(new Coordinate[] { new Coordinate(x0, y0), new Coordinate(x1, y0),
				new Coordinate(x1, y1), new Coordinate(x0, y1), new Coordinate(x0, y0) });
	}

	private static TariffZoneAssigner assigner() {
		return new TariffZoneAssigner(List.of(new TariffZoneAssigner.Zone("70", square(1000, 0, 2000, 1000)),
				new TariffZoneAssigner.Zone("40", square(0, 0, 1000, 1000))));
	}

	@Test
	public void interiorPointsGetTheirZoneAndOutsidePointsNone() {
		TariffZoneAssigner assigner = assigner();
		assertEquals("40", assigner.assign(new Coord(500, 500)).zoneId());
		assertEquals("70", assigner.assign(new Coord(1500, 500)).zoneId());
		assertNull(assigner.assign(new Coord(5000, 5000)).zoneId());
		assertFalse(assigner.assign(new Coord(500, 500)).tie());
	}

	@Test
	public void pointOnSharedBorderPicksLowerZoneIdAndCountsTie() {
		TariffZoneAssigner.Assignment onBorder = assigner().assign(new Coord(1000, 500));
		assertEquals("40", onBorder.zoneId());
		assertTrue(onBorder.tie());
	}

	@Test
	public void duplicateZoneIdsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> new TariffZoneAssigner(List.of(
				new TariffZoneAssigner.Zone("40", square(0, 0, 1, 1)), new TariffZoneAssigner.Zone("40", square(2, 2, 3, 3)))));
	}
}
