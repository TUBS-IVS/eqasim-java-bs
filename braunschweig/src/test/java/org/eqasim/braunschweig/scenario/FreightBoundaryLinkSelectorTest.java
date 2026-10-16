package org.eqasim.braunschweig.scenario;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.util.Set;

import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;

/**
 * Pins the boundary-link rule of issue #444 on a synthetic copy of the A2-west geometry: a dual
 * carriageway with sparse nodes and a residential street whose node lies closer to the boundary
 * point than any motorway node.
 */
public class FreightBoundaryLinkSelectorTest {
	private static final double MINIMUM_CAPACITY_VEHICLES_PER_HOUR = 1000.0;
	private static final double MAXIMUM_DISTANCE_METERS = 300.0;

	/** Region interior lies to the east, like the study area seen from the A2-west gate. */
	private static final Coord FAR_EAST = new Coord(50000.0, 0.0);

	private static Network network() {
		Network network = NetworkUtils.createNetwork();
		NetworkFactory factory = network.getFactory();
		addNode(network, "west", 0.0, 0.0);
		addNode(network, "east", 400.0, 0.0);
		addNode(network, "east_return", 400.0, 20.0);
		addNode(network, "west_return", 0.0, 20.0);
		addNode(network, "residential_south", 190.0, -200.0);
		addNode(network, "residential_north", 195.0, -40.0);
		addLink(network, factory, "a2_eastbound", "west", "east", 6000.0, TransportMode.car);
		addLink(network, factory, "a2_westbound", "east_return", "west_return", 6000.0, TransportMode.car);
		addLink(network, factory, "residential", "residential_south", "residential_north", 600.0,
				TransportMode.car);
		return network;
	}

	private static void addNode(Network network, String id, double x, double y) {
		network.addNode(network.getFactory().createNode(Id.createNodeId(id), new Coord(x, y)));
	}

	private static Link addLink(Network network, NetworkFactory factory, String id, String from, String to,
			double capacityVehiclesPerHour, String mode) {
		Node fromNode = network.getNodes().get(Id.createNodeId(from));
		Node toNode = network.getNodes().get(Id.createNodeId(to));
		Link link = factory.createLink(Id.createLinkId(id), fromNode, toNode);
		link.setAllowedModes(Set.of(mode));
		link.setLength(NetworkUtils.getEuclideanDistance(fromNode.getCoord(), toNode.getCoord()));
		link.setCapacity(capacityVehiclesPerHour);
		network.addLink(link);
		return link;
	}

	private static FreightBoundaryLinkSelector selector(Network network) {
		return new FreightBoundaryLinkSelector(network, MINIMUM_CAPACITY_VEHICLES_PER_HOUR, MAXIMUM_DISTANCE_METERS);
	}

	@Test
	public void matsimNearestNodeSnapPicksTheResidentialStreet() {
		// The defect of #444: XY2Links snaps via the nearest NODE, and residential nodes are dense.
		Link snapped = NetworkUtils.getNearestLink(network(), new Coord(200.0, 1.0));
		assertEquals("residential", snapped.getId().toString());
	}

	@Test
	public void entryPicksTheMotorwayNextToTheBoundaryPoint() {
		Link link = selector(network()).selectEntryLink(new Coord(200.0, 1.0), FAR_EAST).orElseThrow();
		assertEquals("a2_eastbound", link.getId().toString());
	}

	@Test
	public void entryPicksTheCarriagewayHeadingIntoTheTripEvenWhenTheOtherIsCloser() {
		// 5 m from the westbound, 15 m from the eastbound carriageway; the truck travels east.
		Link link = selector(network()).selectEntryLink(new Coord(200.0, 15.0), FAR_EAST).orElseThrow();
		assertEquals("a2_eastbound", link.getId().toString());
	}

	@Test
	public void exitPicksTheCarriagewayLeavingInTheTripDirection() {
		// The truck comes from the east and leaves westwards; the eastbound carriageway is closer.
		Link link = selector(network()).selectExitLink(new Coord(200.0, 1.0), FAR_EAST).orElseThrow();
		assertEquals("a2_westbound", link.getId().toString());
	}

	@Test
	public void noMajorLinkWithinTheSearchDistanceYieldsNoLink() {
		assertFalse(selector(network()).selectEntryLink(new Coord(200.0, 2000.0), FAR_EAST).isPresent());
	}

	@Test
	public void linksWithoutCarModeOrBelowTheMinimumCapacityAreIgnored() {
		Network network = network();
		NetworkFactory factory = network.getFactory();
		addNode(network, "rail_a", 0.0, 2.0);
		addNode(network, "rail_b", 400.0, 2.0);
		addLink(network, factory, "rail", "rail_a", "rail_b", 6000.0, TransportMode.pt);
		addNode(network, "service_a", 0.0, 3.0);
		addNode(network, "service_b", 400.0, 3.0);
		addLink(network, factory, "service_road", "service_a", "service_b", 600.0, TransportMode.car);
		Link link = selector(network).selectEntryLink(new Coord(200.0, 2.5), FAR_EAST).orElseThrow();
		assertEquals("a2_eastbound", link.getId().toString());
	}

	@Test
	public void capacityIsComparedPerHourWhateverTheNetworkCapacityPeriod() {
		// 1500 vehicles per 2 h are 750 veh/h, below the 1000 veh/h minimum.
		Network network = network();
		network.setCapacityPeriod(7200.0);
		network.getLinks().get(Id.createLinkId("a2_eastbound")).setCapacity(1500.0);
		network.getLinks().get(Id.createLinkId("a2_westbound")).setCapacity(1500.0);
		assertFalse(selector(network).selectEntryLink(new Coord(200.0, 1.0), FAR_EAST).isPresent());
	}

	@Test
	public void aLongLinkPassingCloseIsFoundAlthoughItsMidpointIsFar() {
		Network network = NetworkUtils.createNetwork();
		NetworkFactory factory = network.getFactory();
		addNode(network, "far_west", -10000.0, 0.0);
		addNode(network, "near", 100.0, 0.0);
		addLink(network, factory, "long_motorway", "far_west", "near", 6000.0, TransportMode.car);
		Link link = selector(network).selectEntryLink(new Coord(50.0, 10.0), FAR_EAST).orElseThrow();
		assertEquals("long_motorway", link.getId().toString());
	}

	@Test
	public void equalDistancesResolveToTheLowerLinkId() {
		Network network = NetworkUtils.createNetwork();
		NetworkFactory factory = network.getFactory();
		addNode(network, "a", 0.0, 10.0);
		addNode(network, "b", 400.0, 10.0);
		addNode(network, "c", 0.0, -10.0);
		addNode(network, "d", 400.0, -10.0);
		addLink(network, factory, "z_link", "a", "b", 1500.0, TransportMode.car);
		addLink(network, factory, "a_link", "c", "d", 1500.0, TransportMode.car);
		Link link = selector(network).selectEntryLink(new Coord(200.0, 0.0), FAR_EAST).orElseThrow();
		assertEquals("a_link", link.getId().toString());
	}

	@Test
	public void invalidSettingsAreRejected() {
		Network network = network();
		assertThrows(IllegalArgumentException.class,
				() -> new FreightBoundaryLinkSelector(network, MINIMUM_CAPACITY_VEHICLES_PER_HOUR, 0.0));
		assertThrows(IllegalArgumentException.class,
				() -> new FreightBoundaryLinkSelector(network, -1.0, MAXIMUM_DISTANCE_METERS));
	}

	@Test
	public void fallbackShareAboveTheBoundFails() {
		FreightBoundaryLinkSelector.checkFallbackShare(95, 5, 0.05);
		assertThrows(IllegalStateException.class, () -> FreightBoundaryLinkSelector.checkFallbackShare(94, 6, 0.05));
		FreightBoundaryLinkSelector.checkFallbackShare(0, 0, 0.05);
	}
}
