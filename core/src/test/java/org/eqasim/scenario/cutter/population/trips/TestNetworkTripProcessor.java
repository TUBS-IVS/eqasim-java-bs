package org.eqasim.scenario.cutter.population.trips;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.eqasim.core.scenario.cutter.extent.ScenarioExtent;
import org.eqasim.core.scenario.cutter.population.trips.NetworkTripCrossingStatistics;
import org.eqasim.core.scenario.cutter.population.trips.NetworkTripProcessor;
import org.eqasim.core.scenario.cutter.population.trips.crossing.network.NetworkRouteCrossingPoint;
import org.eqasim.core.scenario.cutter.population.trips.crossing.network.NetworkTripCrossingPoint;
import org.eqasim.core.scenario.cutter.population.trips.crossing.network.NetworkTripCrossingPointFinder;
import org.eqasim.core.scenario.cutter.population.trips.crossing.teleportation.TeleportationCrossingPoint;
import org.junit.Assert;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.utils.objectattributes.attributable.Attributes;
import org.mockito.Mockito;

public class TestNetworkTripProcessor {
	static private class NetworkFinderMock implements NetworkTripCrossingPointFinder {
		final private List<NetworkTripCrossingPoint> points = new LinkedList<>();

		@Override
		public List<NetworkTripCrossingPoint> findCrossingPoints(Id<Person> personId, int firstLegIndex,
				Coord startCoord, List<PlanElement> trip, Coord endCoord) {
			return points;
		}

		public void add(NetworkTripCrossingPoint point) {
			points.add(point);
		}

		@Override
		public boolean isInside(List<PlanElement> trip) {
			return true;
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
	public void testCarTripProcessor() {
		NetworkFinderMock finderMock;
		NetworkTripProcessor processor;
		List<PlanElement> result;

		Node mockNode = Mockito.mock(Node.class);
		Mockito.when(mockNode.getCoord()).thenReturn(new Coord(0.0, 0.0));

		Link linkA = Mockito.mock(Link.class);
		Mockito.when(linkA.getId()).thenReturn(Id.createLinkId("A"));
		Mockito.when(linkA.getToNode()).thenReturn(mockNode);
		
		Link linkB = Mockito.mock(Link.class);
		Mockito.when(linkB.getId()).thenReturn(Id.createLinkId("B"));
		Mockito.when(linkB.getToNode()).thenReturn(mockNode);

		Leg mockLeg = Mockito.mock(Leg.class);
		Mockito.when(mockLeg.getDepartureTime()).thenReturn(OptionalTime.defined(100.0));
		Mockito.when(mockLeg.getMode()).thenReturn("car");

		Activity activity = Mockito.mock(Activity.class);
		Mockito.when(activity.getCoord()).thenReturn(new Coord(0.0, 0.0));

		// No crossing points
		finderMock = new NetworkFinderMock();
		processor = new NetworkTripProcessor(finderMock, scenarioExtentMock, new NetworkTripCrossingStatistics());
		result = processor.process(null, 0, activity, Arrays.asList(mockLeg), activity, "car");

		Assert.assertEquals(1, result.size());
		Assert.assertTrue(result.get(0) instanceof Leg);
		Assert.assertEquals("car", ((Leg) result.get(0)).getMode());

		// One crossing point, outgoing
		finderMock = new NetworkFinderMock();
		finderMock.add(new NetworkTripCrossingPoint(new NetworkRouteCrossingPoint(0, linkA, 10.0, 20.0, true), "car", 0));

		processor = new NetworkTripProcessor(finderMock, scenarioExtentMock, new NetworkTripCrossingStatistics());
		result = processor.process(null, 0, activity, Arrays.asList(mockLeg), activity, "car");

		Assert.assertEquals(3, result.size());
		Assert.assertTrue(result.get(0) instanceof Leg);
		Assert.assertTrue(result.get(1) instanceof Activity);
		Assert.assertTrue(result.get(2) instanceof Leg);
		Assert.assertEquals("car", ((Leg) result.get(0)).getMode());
		Assert.assertEquals("outside", ((Activity) result.get(1)).getType());
		Assert.assertEquals("outside", ((Leg) result.get(2)).getMode());
		Assert.assertEquals(20.0, ((Activity) result.get(1)).getEndTime().seconds(), 1e-3);
		Assert.assertEquals(Id.createLinkId("A"), ((Activity) result.get(1)).getLinkId());

		// One crossing point, incoming
		finderMock = new NetworkFinderMock();
		finderMock.add(new NetworkTripCrossingPoint(new NetworkRouteCrossingPoint(0, linkA, 10.0, 20.0, false), "car", 0));

		processor = new NetworkTripProcessor(finderMock, scenarioExtentMock, new NetworkTripCrossingStatistics());
		result = processor.process(null, 0, activity, Arrays.asList(mockLeg), activity, "car");

		Assert.assertEquals(3, result.size());
		Assert.assertTrue(result.get(0) instanceof Leg);
		Assert.assertTrue(result.get(1) instanceof Activity);
		Assert.assertTrue(result.get(2) instanceof Leg);
		Assert.assertEquals("outside", ((Leg) result.get(0)).getMode());
		Assert.assertEquals("outside", ((Activity) result.get(1)).getType());
		Assert.assertEquals("car", ((Leg) result.get(2)).getMode());
		Assert.assertEquals(20.0, ((Activity) result.get(1)).getEndTime().seconds(), 1e-3);
		Assert.assertEquals(Id.createLinkId("A"), ((Activity) result.get(1)).getLinkId());

		// Two crossing points, inside -> outside -> inside
		finderMock = new NetworkFinderMock();
		finderMock.add(new NetworkTripCrossingPoint(new NetworkRouteCrossingPoint(0, linkA, 10.0, 20.0, true), "car", 0));
		finderMock.add(new NetworkTripCrossingPoint(new NetworkRouteCrossingPoint(0, linkB, 30.0, 40.0, false), "car", 0));

		processor = new NetworkTripProcessor(finderMock, scenarioExtentMock, new NetworkTripCrossingStatistics());
		result = processor.process(null, 0, activity, Arrays.asList(mockLeg), activity, "car");

		Assert.assertEquals(5, result.size());
		Assert.assertTrue(result.get(0) instanceof Leg);
		Assert.assertTrue(result.get(1) instanceof Activity);
		Assert.assertTrue(result.get(2) instanceof Leg);
		Assert.assertTrue(result.get(3) instanceof Activity);
		Assert.assertTrue(result.get(4) instanceof Leg);
		Assert.assertEquals("car", ((Leg) result.get(0)).getMode());
		Assert.assertEquals("outside", ((Activity) result.get(1)).getType());
		Assert.assertEquals("outside", ((Leg) result.get(2)).getMode());
		Assert.assertEquals("outside", ((Activity) result.get(3)).getType());
		Assert.assertEquals("car", ((Leg) result.get(4)).getMode());
		Assert.assertEquals(20.0, ((Activity) result.get(1)).getEndTime().seconds(), 1e-3);
		Assert.assertEquals(40.0, ((Activity) result.get(3)).getEndTime().seconds(), 1e-3);
		Assert.assertEquals(Id.createLinkId("A"), ((Activity) result.get(1)).getLinkId());
		Assert.assertEquals(Id.createLinkId("B"), ((Activity) result.get(3)).getLinkId());

		// Crossing point at the access leg
		Leg mockAccessLeg = Mockito.mock(Leg.class);
		Mockito.when(mockAccessLeg.getMode()).thenReturn("walk");

		Activity mockInteractionActivity = Mockito.mock(Activity.class);
		Mockito.when(mockInteractionActivity.getType()).thenReturn("car interaction");

		finderMock = new NetworkFinderMock();
		finderMock.add(
				new NetworkTripCrossingPoint(new TeleportationCrossingPoint(new Coord(0.0, 0.0), 10.0, true), "walk", 0));
		finderMock.add(new NetworkTripCrossingPoint(new NetworkRouteCrossingPoint(0, linkB, 30.0, 40.0, false), "car", 1));

		processor = new NetworkTripProcessor(finderMock, scenarioExtentMock, new NetworkTripCrossingStatistics());
		result = processor.process(null, 0, activity, Arrays.asList(mockAccessLeg, mockInteractionActivity, mockLeg),
				activity, "car");

		Assert.assertEquals(5, result.size());
		Assert.assertTrue(result.get(0) instanceof Leg);
		Assert.assertTrue(result.get(1) instanceof Activity);
		Assert.assertTrue(result.get(2) instanceof Leg);
		Assert.assertTrue(result.get(3) instanceof Activity);
		Assert.assertTrue(result.get(4) instanceof Leg);
		Assert.assertEquals("walk", ((Leg) result.get(0)).getMode());
		Assert.assertEquals("outside", ((Activity) result.get(1)).getType());
		Assert.assertEquals("outside", ((Leg) result.get(2)).getMode());
		Assert.assertEquals("outside", ((Activity) result.get(3)).getType());
		Assert.assertEquals("car", ((Leg) result.get(4)).getMode());
		Assert.assertEquals(10.0, ((Activity) result.get(1)).getEndTime().seconds(), 1e-3);
		Assert.assertEquals(40.0, ((Activity) result.get(3)).getEndTime().seconds(), 1e-3);

		// Crossing point at the egress leg
		Leg mockEgressLeg = Mockito.mock(Leg.class);
		Mockito.when(mockEgressLeg.getMode()).thenReturn("walk");

		finderMock = new NetworkFinderMock();
		finderMock.add(new NetworkTripCrossingPoint(new NetworkRouteCrossingPoint(0, linkB, 30.0, 40.0, true), "car", 0));
		finderMock.add(
				new NetworkTripCrossingPoint(new TeleportationCrossingPoint(new Coord(0.0, 0.0), 70.0, false), "walk", 1));

		processor = new NetworkTripProcessor(finderMock, scenarioExtentMock, new NetworkTripCrossingStatistics());
		result = processor.process(null, 0, activity, Arrays.asList(mockLeg, mockInteractionActivity, mockEgressLeg),
				activity, "car");

		Assert.assertEquals(5, result.size());
		Assert.assertTrue(result.get(0) instanceof Leg);
		Assert.assertTrue(result.get(1) instanceof Activity);
		Assert.assertTrue(result.get(2) instanceof Leg);
		Assert.assertTrue(result.get(3) instanceof Activity);
		Assert.assertTrue(result.get(4) instanceof Leg);
		Assert.assertEquals("car", ((Leg) result.get(0)).getMode());
		Assert.assertEquals("outside", ((Activity) result.get(1)).getType());
		Assert.assertEquals("outside", ((Leg) result.get(2)).getMode());
		Assert.assertEquals("outside", ((Activity) result.get(3)).getType());
		Assert.assertEquals("walk", ((Leg) result.get(4)).getMode());
		Assert.assertEquals(40.0, ((Activity) result.get(1)).getEndTime().seconds(), 1e-3);
		Assert.assertEquals(70.0, ((Activity) result.get(3)).getEndTime().seconds(), 1e-3);
	}

	/**
	 * A routed car trip: access walk, car interaction, car leg, car interaction,
	 * egress walk (eqasim-bs#465).
	 */
	static private List<PlanElement> routedCarTrip() {
		Leg accessLeg = Mockito.mock(Leg.class);
		Mockito.when(accessLeg.getMode()).thenReturn("walk");

		Leg carLeg = Mockito.mock(Leg.class);
		Mockito.when(carLeg.getMode()).thenReturn("car");

		Leg egressLeg = Mockito.mock(Leg.class);
		Mockito.when(egressLeg.getMode()).thenReturn("walk");

		Activity interaction = Mockito.mock(Activity.class);
		Mockito.when(interaction.getType()).thenReturn("car interaction");

		return Arrays.asList(accessLeg, interaction, carLeg, interaction, egressLeg);
	}

	static private Activity activityAt(Coord coord) {
		Activity activity = Mockito.mock(Activity.class);
		Mockito.when(activity.getCoord()).thenReturn(coord);
		return activity;
	}

	@Test
	public void testIncomingAccessWalkCrossingKeepsCarInside() {
		// Home just outside the border, the access walk to the first car link crosses
		// it, the car leg and the work activity are inside: the inside piece contains
		// the car leg, so it must stay a car trip, not a walk to work (person 1125988
		// of the 1 % run walked 107 km).
		NetworkFinderMock finderMock = new NetworkFinderMock();
		finderMock.add(
				new NetworkTripCrossingPoint(new TeleportationCrossingPoint(new Coord(0.0, 0.0), 10.0, false), "walk", 0));

		NetworkTripCrossingStatistics statistics = new NetworkTripCrossingStatistics();
		NetworkTripProcessor processor = new NetworkTripProcessor(finderMock, scenarioExtentMock, statistics);
		List<PlanElement> result = processor.process(null, 0, activityAt(new Coord(0.0, 0.0)), routedCarTrip(),
				activityAt(new Coord(1.0, 1.0)), "car");

		Assert.assertEquals(3, result.size());
		Assert.assertEquals("outside", ((Leg) result.get(0)).getMode());
		Assert.assertEquals("outside", ((Activity) result.get(1)).getType());
		Assert.assertEquals(10.0, ((Activity) result.get(1)).getEndTime().seconds(), 1e-3);
		Assert.assertEquals("car", ((Leg) result.get(2)).getMode());

		Assert.assertEquals(1, statistics.getRoutingModePieces());
		Assert.assertEquals(1, statistics.getCorrectedPieces());
		Assert.assertEquals(0, statistics.getAccessEgressOnlyPieces());
	}

	@Test
	public void testOutgoingEgressWalkCrossingKeepsCarInside() {
		// The way back: the car leg is inside, the egress walk to the home just outside
		// the border crosses it.
		NetworkFinderMock finderMock = new NetworkFinderMock();
		finderMock.add(
				new NetworkTripCrossingPoint(new TeleportationCrossingPoint(new Coord(0.0, 0.0), 70.0, true), "walk", 2));

		NetworkTripCrossingStatistics statistics = new NetworkTripCrossingStatistics();
		NetworkTripProcessor processor = new NetworkTripProcessor(finderMock, scenarioExtentMock, statistics);
		List<PlanElement> result = processor.process(null, 0, activityAt(new Coord(1.0, 1.0)), routedCarTrip(),
				activityAt(new Coord(0.0, 0.0)), "car");

		Assert.assertEquals(3, result.size());
		Assert.assertEquals("car", ((Leg) result.get(0)).getMode());
		Assert.assertEquals("outside", ((Activity) result.get(1)).getType());
		Assert.assertEquals(70.0, ((Activity) result.get(1)).getEndTime().seconds(), 1e-3);
		Assert.assertEquals("outside", ((Leg) result.get(2)).getMode());

		Assert.assertEquals(1, statistics.getCorrectedPieces());
	}

	@Test
	public void testAccessWalkInAndCarOutKeepsCarInside() {
		// Enter on the access walk, leave again by car (persons 1125983 and 1125994 of
		// the 1 % run walked 1.25 km between two border points).
		Link linkA = Mockito.mock(Link.class);
		Mockito.when(linkA.getId()).thenReturn(Id.createLinkId("A"));

		NetworkFinderMock finderMock = new NetworkFinderMock();
		finderMock.add(
				new NetworkTripCrossingPoint(new TeleportationCrossingPoint(new Coord(0.0, 0.0), 10.0, false), "walk", 0));
		finderMock.add(new NetworkTripCrossingPoint(new NetworkRouteCrossingPoint(0, linkA, 30.0, 40.0, true), "car", 1));

		NetworkTripCrossingStatistics statistics = new NetworkTripCrossingStatistics();
		NetworkTripProcessor processor = new NetworkTripProcessor(finderMock, scenarioExtentMock, statistics);
		List<PlanElement> result = processor.process(null, 0, activityAt(new Coord(0.0, 0.0)), routedCarTrip(),
				activityAt(new Coord(2.0, 2.0)), "car");

		Assert.assertEquals(5, result.size());
		Assert.assertEquals("outside", ((Leg) result.get(0)).getMode());
		Assert.assertEquals("car", ((Leg) result.get(2)).getMode());
		Assert.assertEquals("outside", ((Leg) result.get(4)).getMode());

		Assert.assertEquals(1, statistics.getCorrectedPieces());
	}

	@Test
	public void testPieceWithinOneAccessLegKeepsItsMode() {
		// Primary path of the old behaviour that stays: the inside piece lies entirely
		// within the access walk (walk out of the area, car outside, walk back in at the
		// end), so it is a short walk.
		Link linkB = Mockito.mock(Link.class);
		Mockito.when(linkB.getId()).thenReturn(Id.createLinkId("B"));

		NetworkFinderMock finderMock = new NetworkFinderMock();
		finderMock.add(
				new NetworkTripCrossingPoint(new TeleportationCrossingPoint(new Coord(0.0, 0.0), 10.0, true), "walk", 0));
		finderMock.add(
				new NetworkTripCrossingPoint(new TeleportationCrossingPoint(new Coord(1.0, 1.0), 70.0, false), "walk", 2));

		NetworkTripCrossingStatistics statistics = new NetworkTripCrossingStatistics();
		NetworkTripProcessor processor = new NetworkTripProcessor(finderMock, scenarioExtentMock, statistics);
		List<PlanElement> result = processor.process(null, 0, activityAt(new Coord(0.0, 0.0)), routedCarTrip(),
				activityAt(new Coord(1.0, 1.0)), "car");

		Assert.assertEquals(5, result.size());
		Assert.assertEquals("walk", ((Leg) result.get(0)).getMode());
		Assert.assertEquals("outside", ((Leg) result.get(2)).getMode());
		Assert.assertEquals("walk", ((Leg) result.get(4)).getMode());

		Assert.assertEquals(0, statistics.getRoutingModePieces());
		Assert.assertEquals(2, statistics.getAccessEgressOnlyPieces());
	}
}
