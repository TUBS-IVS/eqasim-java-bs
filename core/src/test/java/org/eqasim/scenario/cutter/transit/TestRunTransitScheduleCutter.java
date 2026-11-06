package org.eqasim.scenario.cutter.transit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.eqasim.core.scenario.cutter.extent.ScenarioExtent;
import org.eqasim.core.scenario.cutter.extent.ShapeScenarioExtent;
import org.eqasim.core.scenario.cutter.transit.RunTransitScheduleCutter;
import org.eqasim.core.scenario.cutter.transit.RunTransitScheduleCutter.CutSummary;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.routes.LinkNetworkRouteFactory;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.TransitScheduleFactoryImpl;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

public class TestRunTransitScheduleCutter {
	private static final Id<TransitLine> LINE_ID = Id.create("line", TransitLine.class);
	private static final Id<TransitRoute> ROUTE_ID = Id.create("route", TransitRoute.class);
	private static final Id<TransitRoute> OTHER_ROUTE_ID = Id.create("other", TransitRoute.class);
	// "Goettingen" with the umlaut; the escape keeps this source ASCII
	private static final String GOETTINGEN = "G" + (char) 0xF6 + "ttingen";

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void cutWritesTheScheduleAndTheHubTable() throws IOException {
		TransitSchedule schedule = schedule();
		// the square contains the stops at x = 1 and x = 2 only; "G\u00f6ttingen" is the hub at x = 5
		ScenarioExtent extent = squareExtent(0.5, 2.5);

		CutSummary summary = RunTransitScheduleCutter.cut(schedule, extent, List.of("G\u00f6ttingen"));

		Assert.assertEquals(Set.of(Id.create("fac5", TransitStopFacility.class)), summary.hubStopIds());
		Assert.assertEquals(2, summary.stopsInside());
		Assert.assertEquals(1, summary.stopsHub());
		Assert.assertEquals(2, summary.stopsOtherOutside());
		Assert.assertEquals(1, summary.routesKept());
		Assert.assertEquals(1, summary.routesDropped());

		Path scheduleFile = temporaryFolder.getRoot().toPath().resolve("schedule.xml.gz");
		Path hubsFile = temporaryFolder.getRoot().toPath().resolve("hub_stops.csv");
		RunTransitScheduleCutter.write(schedule, summary, scheduleFile, hubsFile);

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new TransitScheduleReader(scenario).readFile(scheduleFile.toString());
		TransitSchedule written = scenario.getTransitSchedule();

		Assert.assertEquals(Set.of(ROUTE_ID), written.getTransitLines().get(LINE_ID).getRoutes().keySet());
		Assert.assertEquals(List.of("fac1", "fac2", "fac3", "fac4", "fac5"),
				written.getTransitLines().get(LINE_ID).getRoutes().get(ROUTE_ID).getStops().stream()
						.map(stop -> stop.getStopFacility().getId().toString()).collect(Collectors.toList()));

		List<String> lines = Files.readAllLines(hubsFile, StandardCharsets.UTF_8);
		Assert.assertEquals(List.of("name,stop_id,x,y,inside_extent", "G\u00f6ttingen,fac5,5.000,0.000,false"), lines);
		Assert.assertFalse(new String(Files.readAllBytes(hubsFile), StandardCharsets.UTF_8).contains("\r"));
	}

	@Test
	public void theHubTableListsEveryMatchedFacilityEvenWhenItsRouteIsDropped() throws IOException {
		TransitSchedule schedule = schedule();
		// fac9 lies on the dropped route "other": it is a matched hub facility nevertheless
		ScenarioExtent extent = squareExtent(0.5, 2.5);

		CutSummary summary = RunTransitScheduleCutter.cut(schedule, extent, List.of("G\u00f6ttingen", "Nowhere Hbf"));

		Assert.assertEquals(2, summary.hubStopIds().size());

		Path hubsFile = temporaryFolder.getRoot().toPath().resolve("hub_stops.csv");
		RunTransitScheduleCutter.write(schedule, summary, temporaryFolder.getRoot().toPath().resolve("s.xml.gz"),
				hubsFile);

		List<String> lines = Files.readAllLines(hubsFile, StandardCharsets.UTF_8);
		Assert.assertEquals(List.of("name,stop_id,x,y,inside_extent", "G\u00f6ttingen,fac5,5.000,0.000,false",
				"Nowhere Hbf,fac9,9.000,0.000,false"), lines);
	}

	@Test
	public void runKeepsFacilityAttributesAndMinimalTransferTimesFromReadToWrite() throws IOException {
		TransitSchedule schedule = schedule();
		Id<TransitStopFacility> fac1 = Id.create("fac1", TransitStopFacility.class);
		Id<TransitStopFacility> fac2 = Id.create("fac2", TransitStopFacility.class);
		Id<TransitStopFacility> fac9 = Id.create("fac9", TransitStopFacility.class);
		schedule.getFacilities().get(fac1).getAttributes().putAttribute("platform", "3a");
		schedule.getMinimalTransferTimes().set(fac1, fac2, 120.0);
		// fac9 only lies on the dropped route: this relation must go with it
		schedule.getMinimalTransferTimes().set(fac1, fac9, 300.0);

		Path root = temporaryFolder.getRoot().toPath();
		Path input = root.resolve("input_schedule.xml");
		new TransitScheduleWriter(schedule).writeFile(input.toString());
		Path hubNames = root.resolve("hub_stop_names.txt");
		Files.writeString(hubNames, GOETTINGEN + "\n", StandardCharsets.UTF_8);
		Path outputSchedule = root.resolve("cut_schedule.xml");
		Path outputHubs = root.resolve("hub_stops.csv");

		CutSummary summary = RunTransitScheduleCutter.run(input, squareExtent(0.5, 2.5), Optional.of(hubNames),
				outputSchedule, outputHubs);

		Assert.assertEquals(1, summary.routesKept());
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new TransitScheduleReader(scenario).readFile(outputSchedule.toString());
		TransitSchedule written = scenario.getTransitSchedule();

		Assert.assertEquals("3a", written.getFacilities().get(fac1).getAttributes().getAttribute("platform"));
		Assert.assertEquals(120.0, written.getMinimalTransferTimes().get(fac1, fac2), 1e-9);
		Assert.assertTrue(Double.isNaN(written.getMinimalTransferTimes().get(fac1, fac9)));
		Assert.assertEquals(List.of("name,stop_id,x,y,inside_extent", GOETTINGEN + ",fac5,5.000,0.000,false"),
				Files.readAllLines(outputHubs, StandardCharsets.UTF_8));
	}

	@Test
	public void runWithoutHubNamesCutsLikeTheExtentAlone() throws IOException {
		Path root = temporaryFolder.getRoot().toPath();
		Path input = root.resolve("input_schedule.xml");
		new TransitScheduleWriter(schedule()).writeFile(input.toString());

		CutSummary summary = RunTransitScheduleCutter.run(input, squareExtent(0.5, 2.5), Optional.empty(),
				root.resolve("cut_schedule.xml"), root.resolve("hub_stops.csv"));

		Assert.assertTrue(summary.hubStops().isEmpty());
		Assert.assertEquals(2, summary.stopsInside());
		Assert.assertEquals(0, summary.stopsHub());
		Assert.assertEquals(List.of("name,stop_id,x,y,inside_extent"),
				Files.readAllLines(root.resolve("hub_stops.csv"), StandardCharsets.UTF_8));
	}

	@Test
	public void runFailsForAMissingInputFile() throws IOException {
		Path root = temporaryFolder.getRoot().toPath();
		Path input = root.resolve("input_schedule.xml");
		new TransitScheduleWriter(schedule()).writeFile(input.toString());
		ScenarioExtent extent = squareExtent(0.5, 2.5);

		try {
			RunTransitScheduleCutter.run(root.resolve("missing.xml"), extent, Optional.empty(),
					root.resolve("a.xml"), root.resolve("a.csv"));
			Assert.fail("a missing schedule file must fail");
		} catch (IllegalStateException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("--schedule-path"));
		}

		try {
			RunTransitScheduleCutter.run(input, extent, Optional.of(root.resolve("missing.txt")),
					root.resolve("b.xml"), root.resolve("b.csv"));
			Assert.fail("a missing hub names file must fail");
		} catch (IllegalStateException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("--hub-stop-names-path"));
		}

		Assert.assertFalse("nothing is written on a failed run", Files.exists(root.resolve("a.xml")));
		Assert.assertFalse("nothing is written on a failed run", Files.exists(root.resolve("b.xml")));
	}

	@Test
	public void cutFailsWithoutAnyRoute() {
		TransitSchedule schedule = schedule();
		ScenarioExtent extent = squareExtent(100.0, 101.0);

		try {
			RunTransitScheduleCutter.cut(schedule, extent, List.of());
			Assert.fail("a cut without any route must fail");
		} catch (IllegalStateException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("no route"));
		}
	}

	@Test
	public void cutFailsForAnUnmatchedHubName() {
		try {
			RunTransitScheduleCutter.cut(schedule(), squareExtent(0.5, 2.5), List.of("Celle"));
			Assert.fail("an unmatched hub name must fail");
		} catch (IllegalArgumentException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("Celle"));
		}
	}

	private static ScenarioExtent squareExtent(double minX, double maxX) {
		GeometryFactory factory = new GeometryFactory();
		Polygon polygon = factory.createPolygon(new Coordinate[] { new Coordinate(minX, -1.0),
				new Coordinate(maxX, -1.0), new Coordinate(maxX, 1.0), new Coordinate(minX, 1.0),
				new Coordinate(minX, -1.0) });
		return new ShapeScenarioExtent(polygon);
	}

	/**
	 * Route "route" serves x = 1..6 (the hub "G\u00f6ttingen" at x = 5, x = 6 beyond it), route "other" serves
	 * x = 7..9 entirely outside; "Nowhere Hbf" is the name of fac9.
	 */
	private static TransitSchedule schedule() {
		TransitScheduleFactory factory = new TransitScheduleFactoryImpl();
		TransitSchedule schedule = factory.createTransitSchedule();

		for (int x = 1; x <= 9; x++) {
			TransitStopFacility facility = factory.createTransitStopFacility(
					Id.create("fac" + x, TransitStopFacility.class), new Coord(x, 0.0), false);
			facility.setLinkId(Id.createLinkId("link" + x));
			facility.setName(x == 5 ? "G\u00f6ttingen" : x == 9 ? "Nowhere Hbf" : "Stop " + x);
			schedule.addStopFacility(facility);
		}

		TransitLine line = factory.createTransitLine(LINE_ID);
		line.addRoute(route(factory, schedule, ROUTE_ID, 1, 2, 3, 4, 5, 6));
		line.addRoute(route(factory, schedule, OTHER_ROUTE_ID, 7, 8, 9));
		schedule.addTransitLine(line);
		return schedule;
	}

	private static TransitRoute route(TransitScheduleFactory factory, TransitSchedule schedule,
			Id<TransitRoute> routeId, int... stopXs) {
		List<TransitRouteStop> stops = new LinkedList<>();
		List<Id<Link>> linkIds = new LinkedList<>();

		for (int k = 0; k < stopXs.length; k++) {
			TransitStopFacility facility = schedule.getFacilities()
					.get(Id.create("fac" + stopXs[k], TransitStopFacility.class));
			stops.add(factory.createTransitRouteStop(facility, k * 60.0, k * 60.0));
			linkIds.add(Id.createLinkId("link" + stopXs[k]));
		}

		NetworkRoute networkRoute = (NetworkRoute) new LinkNetworkRouteFactory().createRoute(linkIds.get(0),
				linkIds.get(linkIds.size() - 1));
		networkRoute.setLinkIds(linkIds.get(0), linkIds.subList(1, linkIds.size() - 1), linkIds.get(linkIds.size() - 1));

		TransitRoute route = factory.createTransitRoute(routeId, networkRoute, stops, "rail");
		route.addDeparture(factory.createDeparture(Id.create("departure", Departure.class), 8.0 * 3600.0));
		return route;
	}
}
