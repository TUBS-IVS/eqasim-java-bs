package org.eqasim.scenario.cutter.population.trips;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.eqasim.core.misc.Constants;
import org.eqasim.core.scenario.cutter.extent.ScenarioExtent;
import org.eqasim.core.scenario.cutter.population.trips.TransitTripProcessor;
import org.eqasim.core.scenario.cutter.population.trips.crossing.teleportation.TeleportationCrossingPoint;
import org.eqasim.core.scenario.cutter.population.trips.crossing.transit.TransitRouteCrossingPoint;
import org.eqasim.core.scenario.cutter.population.trips.crossing.transit.TransitTripCrossingPoint;
import org.eqasim.core.scenario.cutter.population.trips.crossing.transit.TransitTripCrossingPointFinder;
import org.junit.Assert;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.pt.transitSchedule.TransitScheduleFactoryImpl;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.utils.objectattributes.attributable.Attributes;
import org.mockito.Mockito;

public class TestTransitTripProcessor {
	static private class PublicTransitFinderMock implements TransitTripCrossingPointFinder {
		final private List<TransitTripCrossingPoint> points = new LinkedList<>();

		public void add(TransitTripCrossingPoint point) {
			points.add(point);
		}

		@Override
		public List<TransitTripCrossingPoint> findCrossingPoints(Coord startCoord, List<PlanElement> trip,
				Coord endCoord) {
			return points;
		}
	}

	static ScenarioExtent scenarioExtentMock = new ScenarioExtent() {
		@Override
		public boolean isInside(Coord coord) {
			return false;
		}

		@Override
		public List<Coord> computeEuclideanIntersections(Coord from, Coord to) {
			return null;
		}

		@Override
		public Coord getInteriorPoint() {
			return null;
		}
	};

	@Test
	public void testPublicTransitTripProcessor() {
		PublicTransitFinderMock finderMock;
		TransitTripProcessor processor;
		List<PlanElement> result;

		TransitScheduleFactory factory = new TransitScheduleFactoryImpl();
		TransitStopFacility outsideStopFacility = factory
				.createTransitStopFacility(Id.create("O", TransitStopFacility.class), new Coord(1.0, 0.0), false);
		TransitRouteStop outsideStop = factory.createTransitRouteStop(outsideStopFacility, 0.0, 0.0);
		TransitStopFacility insideStopFacility = factory
				.createTransitStopFacility(Id.create("I", TransitStopFacility.class), new Coord(2.0, 0.0), false);
		TransitRouteStop insideStop = factory.createTransitRouteStop(insideStopFacility, 0.0, 0.0);

		// No crossing points
		finderMock = new PublicTransitFinderMock();
		processor = new TransitTripProcessor(finderMock, scenarioExtentMock, 1.0);
		result = processor.process(new Coord(0.0, 0.0), Collections.emptyList(), new Coord(0.0, 0.0), false, "pt");

		Assert.assertEquals(1, result.size());
		Assert.assertTrue(result.get(0) instanceof Leg);
		Assert.assertEquals("pt", ((Leg) result.get(0)).getMode());

		// One crossing point, outgoing, in vehicle
		finderMock = new PublicTransitFinderMock();
		finderMock.add(new TransitTripCrossingPoint(
				new TransitRouteCrossingPoint(null, null, outsideStop, insideStop, 20.0, 10.0, true)));

		processor = new TransitTripProcessor(finderMock, scenarioExtentMock, 1.0);
		result = processor.process(new Coord(0.0, 0.0), Collections.emptyList(), new Coord(0.0, 0.0), false, "pt");

		Assert.assertEquals(3, result.size());
		Assert.assertTrue(result.get(0) instanceof Leg);
		Assert.assertTrue(result.get(1) instanceof Activity);
		Assert.assertTrue(result.get(2) instanceof Leg);
		Assert.assertEquals("pt", ((Leg) result.get(0)).getMode());
		Assert.assertEquals("outside", ((Activity) result.get(1)).getType());
		Assert.assertEquals("outside", ((Leg) result.get(2)).getMode());
		Assert.assertEquals(9.0, ((Activity) result.get(1)).getEndTime().seconds(), 1e-3);
		Assert.assertEquals(new Coord(2.0, 0.0), ((Activity) result.get(1)).getCoord());

		// One crossing point, outgoing, transfer
		finderMock = new PublicTransitFinderMock();
		finderMock.add(new TransitTripCrossingPoint(new TeleportationCrossingPoint(new Coord(5.0, 0.0), 50.0, true)));

		processor = new TransitTripProcessor(finderMock, scenarioExtentMock, 1.0);
		result = processor.process(new Coord(0.0, 0.0), Collections.emptyList(), new Coord(0.0, 0.0), false, "pt");

		Assert.assertEquals(3, result.size());
		Assert.assertTrue(result.get(0) instanceof Leg);
		Assert.assertTrue(result.get(1) instanceof Activity);
		Assert.assertTrue(result.get(2) instanceof Leg);
		Assert.assertEquals("pt", ((Leg) result.get(0)).getMode());
		Assert.assertEquals("outside", ((Activity) result.get(1)).getType());
		Assert.assertEquals("outside", ((Leg) result.get(2)).getMode());
		Assert.assertEquals(50.0, ((Activity) result.get(1)).getEndTime().seconds(), 1e-3);
		Assert.assertEquals(new Coord(5.0, 0.0), ((Activity) result.get(1)).getCoord());

		// One crossing point, incoming, in vehicle
		finderMock = new PublicTransitFinderMock();
		finderMock.add(new TransitTripCrossingPoint(
				new TransitRouteCrossingPoint(null, null, outsideStop, insideStop, 20.0, 10.0, false)));

		processor = new TransitTripProcessor(finderMock, scenarioExtentMock, 1.0);
		result = processor.process(new Coord(0.0, 0.0), Collections.emptyList(), new Coord(0.0, 0.0), false, "pt");

		Assert.assertEquals(3, result.size());
		Assert.assertTrue(result.get(0) instanceof Leg);
		Assert.assertTrue(result.get(1) instanceof Activity);
		Assert.assertTrue(result.get(2) instanceof Leg);
		Assert.assertEquals("outside", ((Leg) result.get(0)).getMode());
		Assert.assertEquals("outside", ((Activity) result.get(1)).getType());
		Assert.assertEquals("pt", ((Leg) result.get(2)).getMode());
		Assert.assertEquals(9.0, ((Activity) result.get(1)).getEndTime().seconds(), 1e-3);
		Assert.assertEquals(new Coord(2.0, 0.0), ((Activity) result.get(1)).getCoord());

		// One crossing point, incoming, transfer
		finderMock = new PublicTransitFinderMock();
		finderMock.add(new TransitTripCrossingPoint(new TeleportationCrossingPoint(new Coord(5.0, 0.0), 50.0, false)));

		processor = new TransitTripProcessor(finderMock, scenarioExtentMock, 1.0);
		result = processor.process(new Coord(0.0, 0.0), Collections.emptyList(), new Coord(0.0, 0.0), false, "pt");

		Assert.assertEquals(3, result.size());
		Assert.assertTrue(result.get(0) instanceof Leg);
		Assert.assertTrue(result.get(1) instanceof Activity);
		Assert.assertTrue(result.get(2) instanceof Leg);
		Assert.assertEquals("outside", ((Leg) result.get(0)).getMode());
		Assert.assertEquals("outside", ((Activity) result.get(1)).getType());
		Assert.assertEquals("pt", ((Leg) result.get(2)).getMode());
		Assert.assertEquals(50.0, ((Activity) result.get(1)).getEndTime().seconds(), 1e-3);
		Assert.assertEquals(new Coord(5.0, 0.0), ((Activity) result.get(1)).getCoord());

		// Two crossing points, inside -> outside -> inside
		finderMock = new PublicTransitFinderMock();
		finderMock.add(new TransitTripCrossingPoint(
				new TransitRouteCrossingPoint(null, null, outsideStop, insideStop, 20.0, 10.0, true)));
		finderMock.add(new TransitTripCrossingPoint(new TeleportationCrossingPoint(new Coord(5.0, 0.0), 50.0, false)));

		processor = new TransitTripProcessor(finderMock, scenarioExtentMock, 1.0);
		result = processor.process(new Coord(0.0, 0.0), Collections.emptyList(), new Coord(0.0, 0.0), false, "pt");

		Assert.assertEquals(5, result.size());
		Assert.assertTrue(result.get(0) instanceof Leg);
		Assert.assertTrue(result.get(1) instanceof Activity);
		Assert.assertTrue(result.get(2) instanceof Leg);
		Assert.assertTrue(result.get(3) instanceof Activity);
		Assert.assertTrue(result.get(4) instanceof Leg);
		Assert.assertEquals("pt", ((Leg) result.get(0)).getMode());
		Assert.assertEquals("outside", ((Activity) result.get(1)).getType());
		Assert.assertEquals("outside", ((Leg) result.get(2)).getMode());
		Assert.assertEquals("outside", ((Activity) result.get(3)).getType());
		Assert.assertEquals("pt", ((Leg) result.get(4)).getMode());
		Assert.assertEquals(9.0, ((Activity) result.get(1)).getEndTime().seconds(), 1e-3);
		Assert.assertEquals(50.0, ((Activity) result.get(3)).getEndTime().seconds(), 1e-3);
		Assert.assertEquals(new Coord(2.0, 0.0), ((Activity) result.get(1)).getCoord());
		Assert.assertEquals(new Coord(5.0, 0.0), ((Activity) result.get(3)).getCoord());
	}

	// Collapse of the excursions (eqasim-bs#574, design R5)

	private static final Coord INSIDE_ORIGIN = new Coord(0.0, 0.0);
	private static final Coord INSIDE_DESTINATION = new Coord(0.0, 10.0);
	private static final Coord OUTSIDE_ORIGIN = new Coord(100.0, 0.0);
	private static final Coord OUTSIDE_DESTINATION = new Coord(100.0, 10.0);

	/** An extent that contains exactly the two inside activity coordinates. */
	static ScenarioExtent activityExtentMock = new ScenarioExtent() {
		@Override
		public boolean isInside(Coord coord) {
			return coord.equals(INSIDE_ORIGIN) || coord.equals(INSIDE_DESTINATION);
		}

		@Override
		public List<Coord> computeEuclideanIntersections(Coord from, Coord to) {
			return null;
		}

		@Override
		public Coord getInteriorPoint() {
			return null;
		}
	};

	private static TransitTripCrossingPoint teleport(double x, double time, boolean isOutgoing) {
		return new TransitTripCrossingPoint(new TeleportationCrossingPoint(new Coord(x, 0.0), time, isOutgoing));
	}

	private static TransitTripProcessor collapsing(TransitTripCrossingPoint... points) {
		PublicTransitFinderMock finderMock = new PublicTransitFinderMock();

		for (TransitTripCrossingPoint point : points) {
			finderMock.add(point);
		}

		return new TransitTripProcessor(finderMock, activityExtentMock, 1.0, true);
	}

	private static List<PlanElement> process(TransitTripProcessor processor, Coord origin, Coord destination) {
		boolean allOutside = !activityExtentMock.isInside(origin) && !activityExtentMock.isInside(destination);
		return processor.process(origin, Collections.emptyList(), destination, allOutside, "pt");
	}

	private static void assertLeg(PlanElement element, String mode, String collapseCase) {
		Assert.assertTrue(element instanceof Leg);
		Leg leg = (Leg) element;
		Assert.assertEquals(mode, leg.getMode());
		Assert.assertEquals(collapseCase, leg.getAttributes().getAttribute(Constants.CUT_TRANSIT_COLLAPSE_ATTRIBUTE));
	}

	private static void assertOutsideActivity(PlanElement element, double x, double endTime) {
		Assert.assertTrue(element instanceof Activity);
		Activity activity = (Activity) element;
		Assert.assertEquals("outside", activity.getType());
		Assert.assertEquals(new Coord(x, 0.0), activity.getCoord());
		Assert.assertEquals(endTime, activity.getEndTime().seconds(), 1e-6);
	}

	private static void assertCounts(TransitTripProcessor processor, long withCrossings, long insideInside,
			long outsideOrigin, long outsideDestination, long through) {
		Map<String, Long> counts = processor.getCollapseCounts();
		Assert.assertEquals(List.of("trips_with_crossings", "inside_inside", "outside_origin", "outside_destination",
				"through"), List.copyOf(counts.keySet()));
		Assert.assertEquals(withCrossings, (long) counts.get("trips_with_crossings"));
		Assert.assertEquals(insideInside, (long) counts.get("inside_inside"));
		Assert.assertEquals(outsideOrigin, (long) counts.get("outside_origin"));
		Assert.assertEquals(outsideDestination, (long) counts.get("outside_destination"));
		Assert.assertEquals(through, (long) counts.get("through"));
	}

	@Test
	public void anInsideTripWithAnExcursionIsKeptWhole() {
		TransitTripProcessor processor = collapsing(teleport(1.0, 10.0, true), teleport(2.0, 20.0, false));
		List<PlanElement> result = process(processor, INSIDE_ORIGIN, INSIDE_DESTINATION);

		Assert.assertEquals(1, result.size());
		assertLeg(result.get(0), "pt", "inside_inside");
		assertCounts(processor, 1, 1, 0, 0, 0);
	}

	@Test
	public void anOutsideOriginKeepsOnlyTheLastEntry() {
		TransitTripProcessor processor = collapsing(teleport(1.0, 10.0, false), teleport(2.0, 20.0, true),
				teleport(3.0, 30.0, false));
		List<PlanElement> result = process(processor, OUTSIDE_ORIGIN, INSIDE_DESTINATION);

		Assert.assertEquals(3, result.size());
		assertLeg(result.get(0), "outside", null);
		assertOutsideActivity(result.get(1), 3.0, 30.0);
		assertLeg(result.get(2), "pt", "outside_origin");
		assertCounts(processor, 1, 0, 1, 0, 0);
	}

	@Test
	public void anOutsideDestinationKeepsOnlyTheFirstExit() {
		TransitTripProcessor processor = collapsing(teleport(1.0, 10.0, true), teleport(2.0, 20.0, false),
				teleport(3.0, 30.0, true));
		List<PlanElement> result = process(processor, INSIDE_ORIGIN, OUTSIDE_DESTINATION);

		Assert.assertEquals(3, result.size());
		assertLeg(result.get(0), "pt", "outside_destination");
		assertOutsideActivity(result.get(1), 1.0, 10.0);
		assertLeg(result.get(2), "outside", null);
		assertCounts(processor, 1, 0, 0, 1, 0);
	}

	@Test
	public void aThroughTripKeepsFirstEntryAndLastExit() {
		TransitTripProcessor processor = collapsing(teleport(1.0, 10.0, false), teleport(2.0, 20.0, true),
				teleport(3.0, 30.0, false), teleport(4.0, 40.0, true));
		List<PlanElement> result = process(processor, OUTSIDE_ORIGIN, OUTSIDE_DESTINATION);

		Assert.assertEquals(5, result.size());
		assertLeg(result.get(0), "outside", null);
		assertOutsideActivity(result.get(1), 1.0, 10.0);
		assertLeg(result.get(2), "pt", "through");
		assertOutsideActivity(result.get(3), 4.0, 40.0);
		assertLeg(result.get(4), "outside", null);
		assertCounts(processor, 1, 0, 0, 0, 1);
	}

	@Test
	public void aThroughTripWithoutCrossingsStaysAnOutsideLeg() {
		TransitTripProcessor processor = collapsing();
		List<PlanElement> result = process(processor, OUTSIDE_ORIGIN, OUTSIDE_DESTINATION);

		Assert.assertEquals(1, result.size());
		assertLeg(result.get(0), "outside", null);
		assertCounts(processor, 0, 0, 0, 0, 0);
	}

	@Test
	public void aTripWithoutDroppedCrossingsCarriesNoMarker() {
		TransitTripProcessor processor = collapsing(teleport(1.0, 10.0, true));
		List<PlanElement> result = process(processor, INSIDE_ORIGIN, OUTSIDE_DESTINATION);

		Assert.assertEquals(3, result.size());
		assertLeg(result.get(0), "pt", null);
		assertOutsideActivity(result.get(1), 1.0, 10.0);
		assertLeg(result.get(2), "outside", null);
		assertCounts(processor, 1, 0, 0, 0, 0);
	}

	@Test
	public void extentModeKeepsTodaysPieces() {
		PublicTransitFinderMock finderMock = new PublicTransitFinderMock();
		finderMock.add(teleport(1.0, 10.0, true));
		finderMock.add(teleport(2.0, 20.0, false));
		TransitTripProcessor processor = new TransitTripProcessor(finderMock, activityExtentMock, 1.0, false);

		List<PlanElement> result = process(processor, INSIDE_ORIGIN, INSIDE_DESTINATION);

		Assert.assertEquals(5, result.size());
		assertLeg(result.get(0), "pt", null);
		assertOutsideActivity(result.get(1), 1.0, 10.0);
		assertLeg(result.get(2), "outside", null);
		assertOutsideActivity(result.get(3), 2.0, 20.0);
		assertLeg(result.get(4), "pt", null);
		assertCounts(processor, 0, 0, 0, 0, 0);
	}

	@Test
	public void anInsideDestinationWithoutEntryFails() {
		// Inconsistent: origin outside, destination inside, but the only point leaves
		TransitTripProcessor processor = collapsing(teleport(1.0, 10.0, true));
		IllegalStateException e = Assert.assertThrows(IllegalStateException.class,
				() -> process(processor, OUTSIDE_ORIGIN, INSIDE_DESTINATION));
		Assert.assertTrue(e.getMessage().contains("outside_origin"));
	}

	@Test
	public void anInsideOriginWithoutExitFails() {
		// Inconsistent: origin inside, destination outside, but the only point enters
		TransitTripProcessor processor = collapsing(teleport(1.0, 10.0, false));
		IllegalStateException e = Assert.assertThrows(IllegalStateException.class,
				() -> process(processor, INSIDE_ORIGIN, OUTSIDE_DESTINATION));
		Assert.assertTrue(e.getMessage().contains("outside_destination"));
	}
}
