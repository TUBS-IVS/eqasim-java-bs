package org.eqasim.braunschweig.scenario;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;

/**
 * Pins the access-link rule for large facilities (issue #465): a facility next to a 600 veh/h side street
 * gets the closest eligible link of at least the minimum capacity, never a motorway, trunk or ramp.
 */
public class LargeFacilityAccessLinkSelectorTest {
	private static final double MINIMUM_CAPACITY_VEHICLES_PER_HOUR = 1000.0;
	private static final double MAXIMUM_DISTANCE_METERS = 500.0;
	private static final Coord FACILITY = new Coord(0.0, 0.0);

	/** Side street 10 m away, secondary road 200 m away, motorway 50 m away, primary 400 m away. */
	private static Network network() {
		Network network = NetworkUtils.createNetwork();
		addLink(network, "side_street", -100.0, 10.0, 100.0, 10.0, 600.0, "tertiary");
		addLink(network, "secondary", -100.0, 200.0, 100.0, 200.0, 1000.0, "secondary");
		addLink(network, "motorway", -100.0, -50.0, 100.0, -50.0, 6000.0, "motorway");
		addLink(network, "motorway_ramp", -100.0, -40.0, 100.0, -40.0, 1500.0, "motorway_link");
		addLink(network, "trunk", -100.0, -60.0, 100.0, -60.0, 4000.0, "trunk");
		addLink(network, "primary", -100.0, -400.0, 100.0, -400.0, 1500.0, "primary");
		return network;
	}

	static Link addLink(Network network, String id, double fromX, double fromY, double toX, double toY,
			double capacityVehiclesPerHour, String highway) {
		Node from = node(network, id + "_from", fromX, fromY);
		Node to = node(network, id + "_to", toX, toY);
		Link link = network.getFactory().createLink(Id.createLinkId(id), from, to);
		link.setAllowedModes(Set.of(TransportMode.car));
		link.setLength(NetworkUtils.getEuclideanDistance(from.getCoord(), to.getCoord()));
		link.setCapacity(capacityVehiclesPerHour);
		if (highway != null) {
			link.getAttributes().putAttribute(LargeFacilityAccessLinkSelector.HIGHWAY_ATTRIBUTE, highway);
		}
		network.addLink(link);
		return link;
	}

	private static Node node(Network network, String id, double x, double y) {
		Node node = network.getFactory().createNode(Id.createNodeId(id), new Coord(x, y));
		network.addNode(node);
		return node;
	}

	private static LargeFacilityAccessLinkSelector selector(Network network) {
		return new LargeFacilityAccessLinkSelector(network, MINIMUM_CAPACITY_VEHICLES_PER_HOUR, MAXIMUM_DISTANCE_METERS);
	}

	@Test
	public void picksTheClosestEligibleLinkWithEnoughCapacity() {
		// The side street is closer but too small; motorway, ramp and trunk are closer but not eligible.
		Link link = selector(network()).select(FACILITY).orElseThrow();
		assertEquals("secondary", link.getId().toString());
	}

	@Test
	public void excludesMotorwayTrunkAndRampEvenWhenTheyAreTheOnlyCandidates() {
		Network network = NetworkUtils.createNetwork();
		addLink(network, "motorway", -100.0, -50.0, 100.0, -50.0, 6000.0, "motorway");
		addLink(network, "trunk_link", -100.0, -60.0, 100.0, -60.0, 4000.0, "trunk_link");
		assertTrue(selector(network).select(FACILITY).isEmpty());
		assertEquals(0, selector(network).getCandidateLinkCount());
	}

	@Test
	public void returnsEmptyBeyondTheMaximumDistance() {
		Network network = NetworkUtils.createNetwork();
		addLink(network, "far_primary", -100.0, 600.0, 100.0, 600.0, 1500.0, "primary");
		assertTrue(selector(network).select(FACILITY).isEmpty());
	}

	@Test
	public void distanceIsMeasuredToTheSegmentNotToItsNodes() {
		// Long link whose nodes are 1 km away but whose segment passes 100 m from the facility.
		Network network = NetworkUtils.createNetwork();
		addLink(network, "long_secondary", -1000.0, 100.0, 1000.0, 100.0, 1000.0, "secondary");
		assertEquals("long_secondary", selector(network).select(FACILITY).orElseThrow().getId().toString());
	}

	@Test
	public void tiesGoToTheLowerLinkId() {
		Network network = NetworkUtils.createNetwork();
		addLink(network, "b_northbound", -100.0, 100.0, 100.0, 100.0, 1000.0, "secondary");
		addLink(network, "a_southbound", 100.0, 100.0, -100.0, 100.0, 1000.0, "secondary");
		assertEquals("a_southbound", selector(network).select(FACILITY).orElseThrow().getId().toString());
	}

	@Test
	public void linksWithoutTheCarModeAreNotCandidates() {
		Network network = NetworkUtils.createNetwork();
		Link rail = addLink(network, "rail", -100.0, 50.0, 100.0, 50.0, 9999.0, null);
		rail.setAllowedModes(Set.of("rail"));
		assertTrue(selector(network).select(FACILITY).isEmpty());
	}

	@Test
	public void capacityIsConvertedWithTheNetworkCapacityPeriod() {
		// 1500 vehicles per 2 h are 750 veh/h: below the 1000 veh/h minimum.
		Network network = NetworkUtils.createNetwork();
		network.setCapacityPeriod(7200.0);
		Link link = addLink(network, "slow_secondary", -100.0, 100.0, 100.0, 100.0, 1500.0, "secondary");
		LargeFacilityAccessLinkSelector selector = selector(network);
		assertTrue(selector.select(FACILITY).isEmpty());
		assertFalse(selector.hasMinimumCapacity(link));
	}

	@Test
	public void adequacyChecksOnlyTheCapacity() {
		Network network = network();
		LargeFacilityAccessLinkSelector selector = selector(network);
		assertFalse(selector.hasMinimumCapacity(network.getLinks().get(Id.createLinkId("side_street"))));
		assertTrue(selector.hasMinimumCapacity(network.getLinks().get(Id.createLinkId("secondary"))));
	}

	@Test
	public void rejectsInvalidParameters() {
		Network network = network();
		assertThrows(IllegalArgumentException.class, () -> new LargeFacilityAccessLinkSelector(network, -1.0, 500.0));
		assertThrows(IllegalArgumentException.class, () -> new LargeFacilityAccessLinkSelector(network, 1000.0, 0.0));
		assertThrows(IllegalArgumentException.class,
				() -> new LargeFacilityAccessLinkSelector(network, Double.NaN, 500.0));
	}
}
