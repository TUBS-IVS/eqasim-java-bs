package org.eqasim.scenario.cutter.population.trips.crossing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eqasim.core.components.transit.departure.DefaultDepartureFinder;
import org.eqasim.core.components.transit.departure.DepartureFinder;
import org.eqasim.core.scenario.cutter.extent.CircularScenarioExtent;
import org.eqasim.core.scenario.cutter.extent.ScenarioExtent;
import org.eqasim.core.scenario.cutter.population.trips.crossing.teleportation.DefaultTeleportationCrossingPointFinder;
import org.eqasim.core.scenario.cutter.population.trips.crossing.transit.DefaultTransitRouteCrossingPointFinder;
import org.eqasim.core.scenario.cutter.population.trips.crossing.transit.DefaultTransitTripCrossingPointFinder;
import org.eqasim.core.scenario.cutter.population.trips.crossing.transit.KeptStopsTransitTripCrossingPointFinder;
import org.eqasim.core.scenario.cutter.population.trips.crossing.transit.TransitTripCrossingPoint;
import org.eqasim.core.scenario.cutter.transit.TransitScheduleCutter.KeptRange;
import org.junit.Assert;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.TransitScheduleFactoryImpl;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/**
 * The kept-stops finder cuts a pt trip where it leaves or enters the simulated world, whose pt part is the set of
 * kept route POSITIONS (eqasim-bs#574, design R4): a position is in the world iff its route has a kept range that
 * contains its index. Coordinates are in metres; the extent is a circle of 2 km around the origin, so S1 and S2 lie
 * inside and S3 to S6 outside.
 */
public class TestKeptStopsTransitTripCrossingPointFinder {
	private static final ScenarioExtent EXTENT = new CircularScenarioExtent(new Coord(0.0, 0.0), 2000.0);

	private static final Coord HOME_INSIDE = new Coord(0.0, 500.0);
	private static final Coord WORK_INSIDE = new Coord(1000.0, 500.0);
	private static final Coord HOME_OUTSIDE = new Coord(5000.0, 1000.0);
	private static final Coord WORK_OUTSIDE = new Coord(9000.0, 500.0);

	private static final double FIRST_DEPARTURE = 28800.0;
	private static final double SECOND_DEPARTURE = 30600.0;

	private final TransitSchedule schedule;
	private final TransitStopFacility s1;
	private final TransitStopFacility s2;
	private final TransitStopFacility s3;
	private final TransitStopFacility s4;
	private final TransitStopFacility s6;

	private final Id<TransitLine> lineL = Id.create("L", TransitLine.class);
	private final Id<TransitLine> lineM = Id.create("M", TransitLine.class);
	private final Id<TransitRoute> routeR = Id.create("R", TransitRoute.class);
	private final Id<TransitRoute> routeRB = Id.create("RB", TransitRoute.class);
	private final Id<TransitRoute> routeQ = Id.create("Q", TransitRoute.class);

	private final DepartureFinder departureFinder = new DefaultDepartureFinder();

	public TestKeptStopsTransitTripCrossingPointFinder() {
		TransitScheduleFactory factory = new TransitScheduleFactoryImpl();
		schedule = factory.createTransitSchedule();

		s1 = facility(factory, "S1", 500.0);
		s2 = facility(factory, "S2", 1500.0);
		s3 = facility(factory, "S3", 3000.0);
		s4 = facility(factory, "S4", 5000.0);
		TransitStopFacility s5 = facility(factory, "S5", 7000.0);
		s6 = facility(factory, "S6", 9000.0);

		// Line L: R runs S1 -> S5, RB back S5 -> S1; S4 plays the hub that anchors both routes
		TransitLine l = factory.createTransitLine(lineL);
		l.addRoute(route(factory, routeR, List.of(s1, s2, s3, s4, s5), new double[] { 0.0, 120.0, 300.0, 480.0, 600.0 }));
		l.addRoute(route(factory, routeRB, List.of(s5, s4, s3, s2, s1), new double[] { 0.0, 120.0, 300.0, 480.0, 600.0 }));
		schedule.addTransitLine(l);

		// Line M: Q runs S4 -> S6 entirely outside the extent, so the cut drops it (no range)
		TransitLine m = factory.createTransitLine(lineM);
		m.addRoute(route(factory, routeQ, List.of(s4, s6), new double[] { 0.0, 300.0 }));
		schedule.addTransitLine(m);
	}

	private TransitStopFacility facility(TransitScheduleFactory factory, String id, double x) {
		TransitStopFacility facility = factory.createTransitStopFacility(Id.create(id, TransitStopFacility.class),
				new Coord(x, 0.0), false);
		schedule.addStopFacility(facility);
		return facility;
	}

	private TransitRoute route(TransitScheduleFactory factory, Id<TransitRoute> id, List<TransitStopFacility> stops,
			double[] offsets) {
		List<TransitRouteStop> routeStops = new ArrayList<>();

		for (int i = 0; i < stops.size(); i++) {
			routeStops.add(factory.createTransitRouteStop(stops.get(i), offsets[i], offsets[i]));
		}

		TransitRoute route = factory.createTransitRoute(id, null, routeStops, "rail");
		route.addDeparture(factory.createDeparture(Id.create(id + "_1", Departure.class), FIRST_DEPARTURE));
		route.addDeparture(factory.createDeparture(Id.create(id + "_2", Departure.class), SECOND_DEPARTURE));
		return route;
	}

	private static Leg walk(double departureTime, double travelTime) {
		Leg leg = PopulationUtils.createLeg(TransportMode.walk);
		TripStructureUtils.setRoutingMode(leg, TransportMode.pt);
		leg.setDepartureTime(departureTime);
		leg.setTravelTime(travelTime);
		return leg;
	}

	private static Leg ride(Id<TransitLine> line, Id<TransitRoute> route, TransitStopFacility access,
			TransitStopFacility egress, double departureTime, double travelTime) {
		Leg leg = PopulationUtils.createLeg(TransportMode.pt);
		TripStructureUtils.setRoutingMode(leg, TransportMode.pt);
		leg.setRoute(new DefaultTransitPassengerRoute(null, null, access.getId(), egress.getId(), line, route));
		leg.setDepartureTime(departureTime);
		leg.setTravelTime(travelTime);
		return leg;
	}

	private static Activity stage(TransitStopFacility facility) {
		return PopulationUtils.createActivityFromCoord("pt interaction", facility.getCoord());
	}

	private static Map<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> ranges(Id<TransitLine> line,
			Map<Id<TransitRoute>, KeptRange> routes) {
		return Map.of(line, routes);
	}

	private KeptStopsTransitTripCrossingPointFinder finder(
			Map<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> keptRanges) {
		return new KeptStopsTransitTripCrossingPointFinder(schedule, departureFinder, EXTENT, keptRanges);
	}

	/** R keeps S1..S4 (the hub), RB keeps S4..S1; Q has no range. */
	private Map<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> keptWithHub() {
		return ranges(lineL, Map.of(routeR, new KeptRange(0, 3), routeRB, new KeptRange(1, 4)));
	}

	/** Outside home, walk 600 s to the hub S4, ride RB S4 -> S2, walk to an inside destination. */
	private List<PlanElement> outsideHomeViaHubTrip() {
		return List.of(walk(28000.0, 600.0), stage(s4), ride(lineL, routeRB, s4, s2, 28600.0, 360.0), stage(s2),
				walk(29280.0, 300.0));
	}

	@Test
	public void anOutsideHomeWalkingToAKeptHubEntersAtTheStop() {
		List<TransitTripCrossingPoint> points = finder(keptWithHub()).findCrossingPoints(HOME_OUTSIDE,
				outsideHomeViaHubTrip(), WORK_INSIDE);

		Assert.assertEquals(1, points.size());
		TransitTripCrossingPoint point = points.get(0);
		Assert.assertFalse(point.isInVehicle);
		Assert.assertFalse(point.isOutgoing);
		Assert.assertEquals(s4.getCoord(), point.teleportationPoint.coord);
		// The walker reaches the stop: the boarding wait is simulated
		Assert.assertEquals(28000.0 + 600.0, point.teleportationPoint.time, 1e-6);
	}

	@Test
	public void aRideThroughANonKeptStopCrossesTwice() {
		// R keeps only S1..S2, so its positions S3 and S4 are outside the world; RB's range covers the hub S4
		Map<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> keptRanges = ranges(lineL,
				Map.of(routeR, new KeptRange(0, 1), routeRB, new KeptRange(1, 4)));

		// Inside home, ride R S2 -> S4, transfer at S4 to RB, ride back to S1, walk to an inside destination
		List<PlanElement> trip = List.of(walk(28700.0, 60.0), stage(s2), ride(lineL, routeR, s2, s4, 28760.0, 360.0),
				stage(s4), walk(29300.0, 120.0), stage(s4), ride(lineL, routeRB, s4, s1, 29420.0, 480.0), stage(s1),
				walk(31300.0, 60.0));

		List<TransitTripCrossingPoint> points = finder(keptRanges).findCrossingPoints(HOME_INSIDE, trip, WORK_INSIDE);

		Assert.assertEquals(2, points.size());

		// Leaves in the vehicle between S2 (position 1, kept) and S3 (position 2, not kept)
		TransitTripCrossingPoint exit = points.get(0);
		Assert.assertTrue(exit.isInVehicle);
		Assert.assertTrue(exit.isOutgoing);
		Assert.assertEquals(s2.getId(), exit.transitRoutePoint.insideStop.getStopFacility().getId());
		Assert.assertEquals(s3.getId(), exit.transitRoutePoint.outsideStop.getStopFacility().getId());
		Assert.assertEquals(FIRST_DEPARTURE + 120.0, exit.transitRoutePoint.insideDepartureTime, 1e-6);
		Assert.assertEquals(FIRST_DEPARTURE + 300.0, exit.transitRoutePoint.outsideDepartureTime, 1e-6);

		// Re-enters with the transfer walk at the hub S4 (RB position 1, kept), when the walker reaches the stop
		TransitTripCrossingPoint entry = points.get(1);
		Assert.assertFalse(entry.isInVehicle);
		Assert.assertFalse(entry.isOutgoing);
		Assert.assertEquals(s4.getCoord(), entry.teleportationPoint.coord);
		Assert.assertEquals(29300.0 + 120.0, entry.teleportationPoint.time, 1e-6);
	}

	@Test
	public void anEgressWalkToAnOutsideDestinationExitsAtAKeptStop() {
		// Inside home, ride R S2 -> S4 (alighting at 08:08), walk 900 s to an outside destination
		List<PlanElement> trip = List.of(walk(28700.0, 60.0), stage(s2), ride(lineL, routeR, s2, s4, 28760.0, 360.0),
				stage(s4), walk(29280.0, 900.0));

		// S4 kept on R: the walk leaves the world at the stop when it departs
		List<TransitTripCrossingPoint> kept = finder(keptWithHub()).findCrossingPoints(HOME_INSIDE, trip,
				WORK_OUTSIDE);
		Assert.assertEquals(1, kept.size());
		Assert.assertFalse(kept.get(0).isInVehicle);
		Assert.assertTrue(kept.get(0).isOutgoing);
		Assert.assertEquals(s4.getCoord(), kept.get(0).teleportationPoint.coord);
		Assert.assertEquals(29280.0, kept.get(0).teleportationPoint.time, 1e-6);

		// S4 not kept on R: the trip already left in the vehicle, the egress walk gives no point
		List<TransitTripCrossingPoint> notKept = finder(ranges(lineL, Map.of(routeR, new KeptRange(0, 1))))
				.findCrossingPoints(HOME_INSIDE, trip, WORK_OUTSIDE);
		Assert.assertEquals(1, notKept.size());
		Assert.assertTrue(notKept.get(0).isInVehicle);
		Assert.assertTrue(notKept.get(0).isOutgoing);
	}

	@Test
	public void aRideOnADroppedRouteBetweenTwoKeptFacilitiesIsOutsideTheWorld() {
		// Inside home, ride R S1 -> S4 (kept), transfer at S4 to Q (dropped), ride to S6, walk to an outside
		// destination
		List<PlanElement> trip = List.of(walk(28700.0, 60.0), stage(s1), ride(lineL, routeR, s1, s4, 28760.0, 520.0),
				stage(s4), walk(29280.0, 60.0), stage(s4), ride(lineM, routeQ, s4, s6, 29340.0, 1560.0), stage(s6),
				walk(30900.0, 300.0));

		List<TransitTripCrossingPoint> points = finder(keptWithHub()).findCrossingPoints(HOME_INSIDE, trip,
				WORK_OUTSIDE);

		Assert.assertEquals(1, points.size());
		TransitTripCrossingPoint exit = points.get(0);
		Assert.assertFalse(exit.isInVehicle);
		Assert.assertTrue(exit.isOutgoing);
		Assert.assertEquals(s4.getCoord(), exit.teleportationPoint.coord);
		// Leaves when the transfer walk departs from the kept position
		Assert.assertEquals(29280.0, exit.teleportationPoint.time, 1e-6);
	}

	@Test
	public void ridesWithoutWalksChangeMembershipAtTheStop() {
		// Ride R S1 -> S4 (kept), then Q S4 -> S6 (dropped) with no walk between; the trip ends with the ride
		List<PlanElement> trip = List.of(ride(lineL, routeR, s1, s4, 28760.0, 520.0), stage(s4),
				ride(lineM, routeQ, s4, s6, 29340.0, 1560.0));

		List<TransitTripCrossingPoint> points = finder(keptWithHub()).findCrossingPoints(HOME_INSIDE, trip,
				WORK_OUTSIDE);
		Assert.assertEquals(1, points.size());
		Assert.assertTrue(points.get(0).isOutgoing);
		Assert.assertEquals(s4.getCoord(), points.get(0).teleportationPoint.coord);
		Assert.assertEquals(29340.0, points.get(0).teleportationPoint.time, 1e-6);

		// Reverse: enter at the hub S4 of RB from a dropped ride, alight at S2 and end inside: one entry at S4
		List<PlanElement> back = List.of(ride(lineL, routeRB, s4, s2, 28600.0, 360.0));
		List<TransitTripCrossingPoint> entry = finder(keptWithHub()).findCrossingPoints(HOME_OUTSIDE, back,
				WORK_INSIDE);
		Assert.assertEquals(1, entry.size());
		Assert.assertFalse(entry.get(0).isOutgoing);
		Assert.assertEquals(s4.getCoord(), entry.get(0).teleportationPoint.coord);

		// A ride on a kept position that ends at an outside destination exits at the alighting stop on arrival
		List<PlanElement> out = List.of(ride(lineL, routeR, s1, s4, 28760.0, 520.0));
		List<TransitTripCrossingPoint> exit = finder(keptWithHub()).findCrossingPoints(HOME_INSIDE, out,
				WORK_OUTSIDE);
		Assert.assertEquals(1, exit.size());
		Assert.assertTrue(exit.get(0).isOutgoing);
		Assert.assertEquals(s4.getCoord(), exit.get(0).teleportationPoint.coord);
		Assert.assertEquals(28760.0 + 520.0, exit.get(0).teleportationPoint.time, 1e-6);
	}

	@Test
	public void aPointBetweenTwoWalksIsTestedWithTheExtent() {
		// Inside home, walk to a stage activity outside the extent, walk on to an inside destination
		Coord outsideStage = new Coord(5000.0, 2000.0);
		List<PlanElement> trip = List.of(walk(28000.0, 600.0),
				PopulationUtils.createActivityFromCoord("pt interaction", outsideStage), walk(28600.0, 900.0));

		List<TransitTripCrossingPoint> points = finder(keptWithHub()).findCrossingPoints(HOME_INSIDE, trip,
				WORK_INSIDE);

		Assert.assertEquals(2, points.size());
		Assert.assertTrue(points.get(0).isOutgoing);
		Assert.assertEquals(HOME_INSIDE, points.get(0).teleportationPoint.coord);
		Assert.assertEquals(28000.0, points.get(0).teleportationPoint.time, 1e-6);
		Assert.assertFalse(points.get(1).isOutgoing);
		Assert.assertEquals(WORK_INSIDE, points.get(1).teleportationPoint.coord);
		Assert.assertEquals(28600.0 + 900.0, points.get(1).teleportationPoint.time, 1e-6);

		// The same stage activity inside the extent: no crossing
		List<PlanElement> insideTrip = List.of(walk(28000.0, 600.0),
				PopulationUtils.createActivityFromCoord("pt interaction", new Coord(1000.0, 0.0)),
				walk(28600.0, 900.0));
		Assert.assertTrue(
				finder(keptWithHub()).findCrossingPoints(HOME_INSIDE, insideTrip, WORK_INSIDE).isEmpty());
	}

	@Test
	public void anInsideTripOnKeptPositionsHasNoCrossing() {
		List<PlanElement> trip = List.of(walk(28700.0, 60.0), stage(s1), ride(lineL, routeR, s1, s2, 28760.0, 160.0),
				stage(s2), walk(28920.0, 120.0));

		Assert.assertTrue(
				finder(keptWithHub()).findCrossingPoints(HOME_INSIDE, trip, WORK_INSIDE).isEmpty());
	}

	@Test
	public void extentFinderIsUnchanged() {
		// Pin of the extent mode: the polygon finders on the first trip enter in the vehicle between S3 and S2
		DefaultTransitTripCrossingPointFinder extentFinder = new DefaultTransitTripCrossingPointFinder(
				new DefaultTransitRouteCrossingPointFinder(EXTENT, schedule, departureFinder),
				new DefaultTeleportationCrossingPointFinder(EXTENT));

		List<TransitTripCrossingPoint> points = extentFinder.findCrossingPoints(HOME_OUTSIDE, outsideHomeViaHubTrip(),
				WORK_INSIDE);

		Assert.assertEquals(1, points.size());
		TransitTripCrossingPoint entry = points.get(0);
		Assert.assertTrue(entry.isInVehicle);
		Assert.assertFalse(entry.isOutgoing);
		Assert.assertEquals(s2.getId(), entry.transitRoutePoint.insideStop.getStopFacility().getId());
		Assert.assertEquals(s3.getId(), entry.transitRoutePoint.outsideStop.getStopFacility().getId());
		Assert.assertEquals(FIRST_DEPARTURE + 480.0, entry.transitRoutePoint.insideDepartureTime, 1e-6);
		Assert.assertEquals(FIRST_DEPARTURE + 300.0, entry.transitRoutePoint.outsideDepartureTime, 1e-6);
	}
}
