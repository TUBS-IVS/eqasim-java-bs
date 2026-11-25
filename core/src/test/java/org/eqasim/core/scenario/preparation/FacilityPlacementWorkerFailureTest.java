package org.eqasim.core.scenario.preparation;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.function.Supplier;

import org.eqasim.core.scenario.cutter.network.RoadNetwork;
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
import org.matsim.facilities.FacilitiesUtils;

/**
 * A worker exception of the facility placement must fail the stage (eqasim-bs#678). Before, a failing worker thread
 * only ended itself, the stage carried on and left the facilities of that worker without a link.
 */
public class FacilityPlacementWorkerFailureTest {
	private static RoadNetwork network() {
		Network network = NetworkUtils.createNetwork();
		Node a = NetworkUtils.createAndAddNode(network, Id.createNodeId("a"), new Coord(0.0, 0.0));
		Node b = NetworkUtils.createAndAddNode(network, Id.createNodeId("b"), new Coord(1000.0, 0.0));
		Link street = NetworkUtils.createAndAddLink(network, Id.createLinkId("street"), a, b, 1000.0, 10.0, 600.0,
				1.0);
		street.setAllowedModes(Set.of(TransportMode.car));
		return new RoadNetwork(network);
	}

	/** Facilities without a coordinate: the worker fails with a NullPointerException when it looks up the link. */
	private static ActivityFacilities facilitiesWithoutCoordinates() {
		ActivityFacilities facilities = FacilitiesUtils.createActivityFacilities();
		for (int index = 0; index < 4; index++) {
			facilities.addActivityFacility(facilities.getFactory()
					.createActivityFacility(Id.create("facility_" + index, ActivityFacility.class), (Coord) null));
		}
		return facilities;
	}

	/**
	 * Runs the placement in a non-daemon thread, like the JVM main thread of the preparation runner (a JUnit timeout
	 * runs the test in a daemon thread), and rethrows its failure.
	 */
	/** A facility whose coordinate lookup throws an Error (not an Exception) inside the worker. */
	private static ActivityFacilities facilitiesFailingWithAnError() {
		ActivityFacilities facilities = FacilitiesUtils.createActivityFacilities();
		ActivityFacility facility = mock(ActivityFacility.class);
		when(facility.getId()).thenReturn(Id.create("facility_error", ActivityFacility.class));
		when(facility.getLinkId()).thenReturn(null);
		when(facility.getCoord()).thenThrow(new AssertionError("worker error"));
		facilities.addActivityFacility(facility);
		return facilities;
	}

	private static void placeInNonDaemonThread(int threads) throws Throwable {
		placeInNonDaemonThread(threads, FacilityPlacementWorkerFailureTest::facilitiesWithoutCoordinates);
	}

	private static void placeInNonDaemonThread(int threads, Supplier<ActivityFacilities> facilities) throws Throwable {
		Throwable[] failure = new Throwable[1];
		Thread runner = new Thread(() -> {
			try {
				new FacilityPlacement(threads, 1, network(), link -> true).run(facilities.get());
			} catch (Throwable error) {
				failure[0] = error;
			}
		}, "facility-placement-test-main");
		runner.setDaemon(false);
		runner.start();
		runner.join();
		if (failure[0] != null) {
			throw failure[0];
		}
	}

	@Test(timeout = 60000)
	public void aWorkerFailureWithOneThreadFailsThePlacement() {
		RuntimeException error = assertThrows(RuntimeException.class, () -> placeInNonDaemonThread(1));
		assertTrue(String.valueOf(error.getCause()), error.getCause() instanceof NullPointerException);
	}

	@Test(timeout = 60000)
	public void aWorkerFailureWithSeveralThreadsFailsThePlacement() {
		RuntimeException error = assertThrows(RuntimeException.class, () -> placeInNonDaemonThread(2));
		assertTrue(String.valueOf(error.getCause()), error.getCause() instanceof NullPointerException);
	}

	@Test(timeout = 60000)
	public void anErrorInAWorkerFailsThePlacement() {
		RuntimeException error = assertThrows(RuntimeException.class,
				() -> placeInNonDaemonThread(1, FacilityPlacementWorkerFailureTest::facilitiesFailingWithAnError));
		assertTrue(String.valueOf(error.getCause()), error.getCause() instanceof AssertionError);
	}
}
