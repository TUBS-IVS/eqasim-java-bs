package org.eqasim.scenario.cutter.transit;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eqasim.core.scenario.cutter.extent.ScenarioExtent;
import org.eqasim.core.scenario.cutter.transit.DefaultStopSequenceCrossingPointFinder;
import org.eqasim.core.scenario.cutter.transit.TransitScheduleCutter;
import org.junit.Assert;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.population.routes.LinkNetworkRouteFactory;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.pt.transitSchedule.TransitScheduleFactoryImpl;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/**
 * Cuts a single-route schedule whose stops lie at x = 1, 2, ... (stop facility "fac" + x on link "link" + x)
 * with an extent that contains exactly the listed x values, and checks which stops the cut route keeps.
 */
public class TestTransitScheduleCutter {
	private static final Id<TransitLine> LINE_ID = Id.create("line", TransitLine.class);
	private static final Id<TransitRoute> ROUTE_ID = Id.create("route", TransitRoute.class);
	private static final double DEPARTURE_TIME_S = 8.0 * 3600.0;
	private static final double SECONDS_PER_STOP = 60.0;
	private static final double DWELL_S = 10.0;

	@Test
	public void keepsTheLastInsideStopOfARouteThatLeavesTheExtent() {
		// inside, inside, outside: e.g. a train from Wolfsburg via Braunschweig to Hanover
		TransitSchedule schedule = cut(new int[] { 1, 2, 3 }, 1, 2);

		TransitRoute route = cutRoute(schedule);
		Assert.assertEquals(List.of("fac1", "fac2"), stopIds(route));
		Assert.assertEquals(Id.createLinkId("link1"), route.getRoute().getStartLinkId());
		Assert.assertEquals(Id.createLinkId("link2"), route.getRoute().getEndLinkId());
	}

	@Test
	public void keepsAllInsideStopsOfARouteThatPassesThroughTheExtent() {
		// outside, inside, inside, outside
		TransitSchedule schedule = cut(new int[] { 1, 2, 3, 4 }, 2, 3);

		TransitRoute route = cutRoute(schedule);
		Assert.assertEquals(List.of("fac2", "fac3"), stopIds(route));
		Assert.assertEquals(Id.createLinkId("link2"), route.getRoute().getStartLinkId());
		Assert.assertEquals(Id.createLinkId("link3"), route.getRoute().getEndLinkId());
		Assert.assertEquals(Set.of("fac2", "fac3"), facilityIds(schedule));
	}

	@Test
	public void keepsTheFirstInsideStopOfARouteThatEntersTheExtent() {
		// outside, inside, inside
		TransitSchedule schedule = cut(new int[] { 1, 2, 3 }, 2, 3);

		Assert.assertEquals(List.of("fac2", "fac3"), stopIds(cutRoute(schedule)));
	}

	@Test
	public void keepsTheTimetableAtTheStopsOfARouteThatEntersTheExtent() {
		// outside, inside, inside, outside: the vehicle reaches fac2 at 08:01 and fac3 at 08:02
		TransitSchedule schedule = cut(new int[] { 1, 2, 3, 4 }, 2, 3);

		TransitRoute route = cutRoute(schedule);
		assertTimetable(route, 0, 1);
		assertTimetable(route, 1, 2);
	}

	@Test
	public void keepsTheTimetableAtTheStopsOfARouteThatLeavesTheExtent() {
		// inside, inside, outside
		TransitSchedule schedule = cut(new int[] { 1, 2, 3 }, 1, 2);

		TransitRoute route = cutRoute(schedule);
		assertTimetable(route, 0, 0);
		assertTimetable(route, 1, 1);
	}

	@Test
	public void dropsARouteThatKeepsOnlyOneInsideStop() {
		// outside, inside, outside: a single stop inside cannot form a route
		TransitSchedule schedule = cut(new int[] { 1, 2, 3 }, 2);

		Assert.assertNull(schedule.getTransitLines().get(LINE_ID));
		Assert.assertTrue(schedule.getFacilities().isEmpty());
	}

	private static TransitSchedule cut(int[] stopXs, int... insideXs) {
		TransitScheduleFactory factory = new TransitScheduleFactoryImpl();
		TransitSchedule schedule = factory.createTransitSchedule();

		List<TransitRouteStop> stops = new LinkedList<>();
		List<Id<Link>> linkIds = new LinkedList<>();
		for (int k = 0; k < stopXs.length; k++) {
			Id<Link> linkId = Id.createLinkId("link" + stopXs[k]);
			TransitStopFacility facility = factory.createTransitStopFacility(
					Id.create("fac" + stopXs[k], TransitStopFacility.class), new Coord(stopXs[k], 0.0), false);
			facility.setLinkId(linkId);
			schedule.addStopFacility(facility);
			// Arrive DWELL_S before departing (except at the first stop), as stops of a GTFS-converted
			// schedule may, so the timetable tests cover arrival and departure offsets separately.
			TransitRouteStop stop = factory.createTransitRouteStop(facility,
					k == 0 ? 0.0 : k * SECONDS_PER_STOP - DWELL_S, k * SECONDS_PER_STOP);
			stop.setAwaitDepartureTime(true);
			stops.add(stop);
			linkIds.add(linkId);
		}

		NetworkRoute networkRoute = (NetworkRoute) new LinkNetworkRouteFactory().createRoute(linkIds.get(0),
				linkIds.get(linkIds.size() - 1));
		networkRoute.setLinkIds(linkIds.get(0), linkIds.subList(1, linkIds.size() - 1), linkIds.get(linkIds.size() - 1));

		TransitRoute route = factory.createTransitRoute(ROUTE_ID, networkRoute, stops, "rail");
		route.addDeparture(factory.createDeparture(Id.create("departure", Departure.class), DEPARTURE_TIME_S));
		TransitLine line = factory.createTransitLine(LINE_ID);
		line.addRoute(route);
		schedule.addTransitLine(line);

		ScenarioExtent extent = extentContaining(insideXs);
		new TransitScheduleCutter(extent, new DefaultStopSequenceCrossingPointFinder(extent)).run(schedule);
		return schedule;
	}

	private static ScenarioExtent extentContaining(int... insideXs) {
		Set<Double> inside = new HashSet<>();
		for (int x : insideXs) {
			inside.add((double) x);
		}

		return new ScenarioExtent() {
			@Override
			public boolean isInside(Coord coord) {
				return inside.contains(coord.getX());
			}

			@Override
			public List<Coord> computeEuclideanIntersections(Coord from, Coord to) {
				throw new IllegalStateException("the transit schedule cutter does not intersect geometries");
			}

			@Override
			public Coord getInteriorPoint() {
				throw new IllegalStateException("the transit schedule cutter does not need an interior point");
			}
		};
	}

	private static TransitRoute cutRoute(TransitSchedule schedule) {
		TransitLine line = schedule.getTransitLines().get(LINE_ID);
		Assert.assertNotNull("the cut dropped the whole line", line);
		return line.getRoutes().get(ROUTE_ID);
	}

	private static List<String> stopIds(TransitRoute route) {
		return route.getStops().stream().map(stop -> stop.getStopFacility().getId().toString()).collect(Collectors.toList());
	}

	private static Set<String> facilityIds(TransitSchedule schedule) {
		return schedule.getFacilities().keySet().stream().map(Id::toString).collect(Collectors.toSet());
	}

	/**
	 * The cut route's stop at {@code cutIndex} is served at the times of the original route's stop at
	 * {@code originalIndex} (departure time plus the stop's offsets), and still awaits its departure time.
	 */
	private static void assertTimetable(TransitRoute route, int cutIndex, int originalIndex) {
		double departureTime = route.getDepartures().values().iterator().next().getDepartureTime();
		TransitRouteStop stop = route.getStops().get(cutIndex);
		double expectedDeparture = DEPARTURE_TIME_S + originalIndex * SECONDS_PER_STOP;
		double expectedArrival = originalIndex == 0 ? DEPARTURE_TIME_S : expectedDeparture - DWELL_S;
		Assert.assertEquals("departure at " + stop.getStopFacility().getId(), expectedDeparture,
				departureTime + stop.getDepartureOffset().seconds(), 1e-9);
		Assert.assertEquals("arrival at " + stop.getStopFacility().getId(), expectedArrival,
				departureTime + stop.getArrivalOffset().seconds(), 1e-9);
		Assert.assertTrue(stop.isAwaitDepartureTime());
	}
}
