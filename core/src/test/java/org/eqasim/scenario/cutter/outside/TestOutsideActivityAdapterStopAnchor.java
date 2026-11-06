package org.eqasim.scenario.cutter.outside;

import java.util.Set;

import org.eqasim.core.misc.Constants;
import org.eqasim.core.scenario.cutter.network.RoadNetwork;
import org.eqasim.core.scenario.cutter.outside.OutsideActivityAdapter;
import org.junit.Assert;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;

/**
 * The outside activity adapter anchors an {@code outside} activity that the transit cut created at a stop (attribute
 * {@link Constants#CUT_TRANSIT_STOP_ATTRIBUTE}) at the stop's coordinate with one facility per stop, linked to the
 * nearest ROAD link; every other outside activity keeps one facility per nearest road link at the link's midpoint
 * (eqasim-bs#574). Coordinates in metres: the road runs from (0, 0) to (1000, 0), a rail link from (0, 100) to
 * (1000, 100), and the stop lies at (500, 90), nearer to the rail link than to the road.
 */
public class TestOutsideActivityAdapterStopAnchor {
	private static final Coord STOP = new Coord(500.0, 90.0);
	private static final Coord OFF_STOP = new Coord(200.0, 50.0);
	private static final Id<Link> ROAD = Id.createLinkId("road");

	private static RoadNetwork roadNetwork() {
		Network network = NetworkUtils.createNetwork();
		Node a = NetworkUtils.createAndAddNode(network, Id.createNodeId("a"), new Coord(0.0, 0.0));
		Node b = NetworkUtils.createAndAddNode(network, Id.createNodeId("b"), new Coord(1000.0, 0.0));
		Node c = NetworkUtils.createAndAddNode(network, Id.createNodeId("c"), new Coord(0.0, 100.0));
		Node d = NetworkUtils.createAndAddNode(network, Id.createNodeId("d"), new Coord(1000.0, 100.0));

		Link road = NetworkUtils.createAndAddLink(network, ROAD, a, b, 1000.0, 10.0, 1000.0, 1.0);
		road.setAllowedModes(Set.of(TransportMode.car));
		Link rail = NetworkUtils.createAndAddLink(network, Id.createLinkId("rail"), c, d, 1000.0, 30.0, 1000.0, 1.0);
		rail.setAllowedModes(Set.of("rail"));

		return new RoadNetwork(network);
	}

	private static Scenario scenario() {
		return ScenarioUtils.createScenario(ConfigUtils.createConfig());
	}

	private static Activity outside(Coord coord, String stopId) {
		Activity activity = PopulationUtils.createActivityFromCoord("outside", coord);
		activity.setEndTime(28800.0);

		if (stopId != null) {
			activity.getAttributes().putAttribute(Constants.CUT_TRANSIT_STOP_ATTRIBUTE, stopId);
		}

		return activity;
	}

	/** Adds a person whose plan starts with {@code first}, rides pt and ends at a work activity. */
	private static Activity addPerson(Population population, String personId, Activity first) {
		Person person = population.getFactory().createPerson(Id.createPersonId(personId));
		Plan plan = population.getFactory().createPlan();
		plan.addActivity(first);
		plan.addLeg(PopulationUtils.createLeg(TransportMode.pt));
		plan.addActivity(PopulationUtils.createActivityFromCoord("work", new Coord(300.0, -50.0)));
		person.addPlan(plan);
		population.addPerson(person);
		return first;
	}

	@Test
	public void anActivityAtAStopIsAnchoredAtTheStopWithTheNearestRoadLink() {
		Scenario scenario = scenario();
		Population population = scenario.getPopulation();
		ActivityFacilities facilities = scenario.getActivityFacilities();

		Activity first = addPerson(population, "p1", outside(STOP, "S4"));
		Activity second = addPerson(population, "p2", outside(new Coord(500.0, 90.0), "S4"));
		Activity unanchored = addPerson(population, "p3", outside(OFF_STOP, null));

		OutsideActivityAdapter adapter = new OutsideActivityAdapter(roadNetwork());
		adapter.run(population, facilities);

		// (1) One facility for the stop, at the stop's coordinate, on the road link
		Id<ActivityFacility> stopFacilityId = Id.create("outside_stop_S4", ActivityFacility.class);
		ActivityFacility stopFacility = facilities.getFacilities().get(stopFacilityId);
		Assert.assertNotNull(stopFacility);
		Assert.assertEquals(STOP, stopFacility.getCoord());
		Assert.assertEquals(ROAD, stopFacility.getLinkId());
		Assert.assertTrue(stopFacility.getActivityOptions().containsKey("outside"));

		Assert.assertEquals(STOP, first.getCoord());
		Assert.assertEquals(ROAD, first.getLinkId());
		Assert.assertEquals(stopFacilityId, first.getFacilityId());

		// (2) The second activity at the same stop reuses the facility
		Assert.assertEquals(STOP, second.getCoord());
		Assert.assertEquals(ROAD, second.getLinkId());
		Assert.assertEquals(stopFacilityId, second.getFacilityId());

		// (3) Without the attribute: today's facility per road link, at the link's midpoint
		Id<ActivityFacility> linkFacilityId = Id.create("outside_1", ActivityFacility.class);
		Assert.assertEquals(linkFacilityId, unanchored.getFacilityId());
		Assert.assertEquals(new Coord(500.0, 0.0), unanchored.getCoord());
		Assert.assertEquals(ROAD, unanchored.getLinkId());
		Assert.assertEquals(new Coord(500.0, 0.0), facilities.getFacilities().get(linkFacilityId).getCoord());

		// One facility for the stop plus one for the link, nothing else
		Assert.assertEquals(2, facilities.getFacilities().size());

		// (5) The counts
		Assert.assertEquals(3, adapter.getOutsideActivities());
		Assert.assertEquals(2, adapter.getStopAnchoredActivities());
	}

	@Test
	public void aTrailingOutsideActivityAtAStopKeepsNoEndTime() {
		Scenario scenario = scenario();
		Population population = scenario.getPopulation();
		Person person = population.getFactory().createPerson(Id.createPersonId("p1"));
		Plan plan = population.getFactory().createPlan();
		Activity work = PopulationUtils.createActivityFromCoord("work", new Coord(300.0, -50.0));
		work.setEndTime(28000.0);
		plan.addActivity(work);
		plan.addLeg(PopulationUtils.createLeg(TransportMode.pt));
		Activity last = outside(STOP, "S4");
		plan.addActivity(last);
		person.addPlan(plan);
		population.addPerson(person);

		new OutsideActivityAdapter(roadNetwork()).run(population, scenario.getActivityFacilities());

		Assert.assertEquals(Id.create("outside_stop_S4", ActivityFacility.class), last.getFacilityId());
		Assert.assertTrue(last.getEndTime().isUndefined());
	}

	@Test
	public void aStopWithTwoCoordinatesFails() {
		Scenario scenario = scenario();
		Population population = scenario.getPopulation();
		addPerson(population, "p1", outside(STOP, "S4"));
		addPerson(population, "p2", outside(OFF_STOP, "S4"));

		OutsideActivityAdapter adapter = new OutsideActivityAdapter(roadNetwork());
		IllegalStateException e = Assert.assertThrows(IllegalStateException.class,
				() -> adapter.run(population, scenario.getActivityFacilities()));
		Assert.assertTrue(e.getMessage(), e.getMessage().contains("S4"));
		Assert.assertTrue(e.getMessage(), e.getMessage().contains(STOP.toString()));
		Assert.assertTrue(e.getMessage(), e.getMessage().contains(OFF_STOP.toString()));
	}

	@Test
	public void aStopFacilityIdTakenByTheInputFails() {
		Scenario scenario = scenario();
		ActivityFacilities facilities = scenario.getActivityFacilities();
		facilities.addActivityFacility(facilities.getFactory()
				.createActivityFacility(Id.create("outside_stop_S4", ActivityFacility.class), STOP, ROAD));
		addPerson(scenario.getPopulation(), "p1", outside(STOP, "S4"));

		OutsideActivityAdapter adapter = new OutsideActivityAdapter(roadNetwork());
		IllegalStateException e = Assert.assertThrows(IllegalStateException.class,
				() -> adapter.run(scenario.getPopulation(), facilities));
		Assert.assertTrue(e.getMessage(), e.getMessage().contains("outside_stop_S4"));
	}
}
