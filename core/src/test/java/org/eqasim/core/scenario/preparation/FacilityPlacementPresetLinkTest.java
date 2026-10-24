package org.eqasim.core.scenario.preparation;

import org.eqasim.core.scenario.cutter.network.RoadNetwork;
import org.junit.Assert;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.ActivityFacilityImpl;
import org.matsim.facilities.FacilitiesUtils;

import java.util.Set;

/**
 * Verifies that the facility placement keeps a link the facilities file already carries (TUBS-IVS/eqasim-bs#465,
 * ADR-0167) and still places every facility without one on the closest eligible link.
 */
public class FacilityPlacementPresetLinkTest {
	private static Network network() {
		Network network = NetworkUtils.createNetwork();
		Node a = NetworkUtils.createAndAddNode(network, Id.createNodeId("a"), new Coord(0.0, 0.0));
		Node b = NetworkUtils.createAndAddNode(network, Id.createNodeId("b"), new Coord(1000.0, 0.0));
		Node c = NetworkUtils.createAndAddNode(network, Id.createNodeId("c"), new Coord(0.0, 1000.0));

		Link street = NetworkUtils.createAndAddLink(network, Id.createLinkId("street"), a, b, 1000.0, 10.0, 600.0,
				1.0);
		street.setAllowedModes(Set.of(TransportMode.car));

		Link motorway = NetworkUtils.createAndAddLink(network, Id.createLinkId("motorway"), a, c, 1000.0, 30.0,
				4000.0, 2.0);
		motorway.setAllowedModes(Set.of(TransportMode.car));
		motorway.getAttributes().putAttribute("osm:way:highway", "motorway");

		Link rail = NetworkUtils.createAndAddLink(network, Id.createLinkId("rail"), b, c, 1414.0, 30.0, 4000.0, 1.0);
		rail.setAllowedModes(Set.of(TransportMode.pt));

		street.getAttributes().putAttribute("osm:way:highway", "residential");
		return network;
	}

	private static ActivityFacilities facilities(String presetLinkOfGate) {
		ActivityFacilities facilities = FacilitiesUtils.createActivityFacilities();
		ActivityFacility home = facilities.getFactory().createActivityFacility(Id.create("home_1", ActivityFacility.class),
				new Coord(10.0, 900.0));
		facilities.addActivityFacility(home);

		ActivityFacility gate = facilities.getFactory().createActivityFacility(
				Id.create("portal_gate_0000", ActivityFacility.class), new Coord(10.0, 900.0));
		if (presetLinkOfGate != null) {
			((ActivityFacilityImpl) gate).setLinkId(Id.createLinkId(presetLinkOfGate));
		}
		facilities.addActivityFacility(gate);
		return facilities;
	}

	private static FacilityPlacement placement(Network network) {
		RoadNetwork roadNetwork = new RoadNetwork(network);
		return new FacilityPlacement(1, 10, roadNetwork, new FacilityPlacement.OSMFacilityPlacementVoter(roadNetwork));
	}

	@Test
	public void testPresetLinkIsKeptAndOthersArePlaced() throws InterruptedException {
		ActivityFacilities facilities = facilities("motorway");
		FacilityPlacement placement = placement(network());
		placement.run(facilities);

		// The preset motorway link survives although the voter never places a facility on a motorway.
		Assert.assertEquals(Id.createLinkId("motorway"),
				facilities.getFacilities().get(Id.create("portal_gate_0000", ActivityFacility.class)).getLinkId());
		// The facility without a link gets the only eligible link (the motorway is excluded by the voter).
		Assert.assertEquals(Id.createLinkId("street"),
				facilities.getFacilities().get(Id.create("home_1", ActivityFacility.class)).getLinkId());
		Assert.assertEquals(1, placement.getNumberOfPresetLinks());
		Assert.assertEquals(1, placement.getNumberOfPlacedFacilities());
	}

	@Test
	public void testWithoutPresetLinkEveryFacilityIsPlaced() throws InterruptedException {
		ActivityFacilities facilities = facilities(null);
		FacilityPlacement placement = placement(network());
		placement.run(facilities);

		Assert.assertEquals(Id.createLinkId("street"),
				facilities.getFacilities().get(Id.create("portal_gate_0000", ActivityFacility.class)).getLinkId());
		Assert.assertEquals(0, placement.getNumberOfPresetLinks());
		Assert.assertEquals(2, placement.getNumberOfPlacedFacilities());
	}

	@Test(expected = IllegalStateException.class)
	public void testPresetLinkMissingFromTheNetworkFails() throws InterruptedException {
		placement(network()).run(facilities("no_such_link"));
	}

	@Test(expected = IllegalStateException.class)
	public void testPresetLinkWithoutCarFails() throws InterruptedException {
		placement(network()).run(facilities("rail"));
	}
}
