package org.eqasim.braunschweig.scenario;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.eqasim.braunschweig.fares.zonal.VrbZoneFareCostModel;
import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.TransitScheduleFactoryImpl;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

public class AddVrbTariffZoneInformationTest {
	private static final GeometryFactory FACTORY = new GeometryFactory();

	private static TariffZoneAssigner assigner() {
		Geometry square = FACTORY.createPolygon(new Coordinate[] { new Coordinate(0, 0), new Coordinate(1000, 0),
				new Coordinate(1000, 1000), new Coordinate(0, 1000), new Coordinate(0, 0) });
		return new TariffZoneAssigner(List.of(new TariffZoneAssigner.Zone("40", square)));
	}

	/** A schedule attributed before (e.g. an ON-prepared scenario) carries zone "70" on every facility. */
	private static TransitStopFacility facilityWithStaleZone(double x, double y) {
		TransitStopFacility facility = new TransitScheduleFactoryImpl().createTransitStopFacility(
				Id.create("stop", TransitStopFacility.class), new Coord(x, y), false);
		facility.getAttributes().putAttribute(VrbZoneFareCostModel.ZONE_ATTRIBUTE, "70");
		return facility;
	}

	@Test
	public void aFacilityOutsideEveryPolygonLosesAStaleZone() {
		TransitStopFacility facility = facilityWithStaleZone(5000, 5000);

		TariffZoneAssigner.Assignment assignment = AddVrbTariffZoneInformation.attributeZone(facility, assigner());

		assertNull(assignment.zoneId());
		assertNull(facility.getAttributes().getAttribute(VrbZoneFareCostModel.ZONE_ATTRIBUTE));
	}

	@Test
	public void aFacilityInsideAPolygonGetsThatZoneOverAStaleOne() {
		TransitStopFacility facility = facilityWithStaleZone(500, 500);

		TariffZoneAssigner.Assignment assignment = AddVrbTariffZoneInformation.attributeZone(facility, assigner());

		assertEquals("40", assignment.zoneId());
		assertEquals("40", facility.getAttributes().getAttribute(VrbZoneFareCostModel.ZONE_ATTRIBUTE));
	}
}
