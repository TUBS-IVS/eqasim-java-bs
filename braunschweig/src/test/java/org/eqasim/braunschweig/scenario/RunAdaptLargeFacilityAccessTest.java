package org.eqasim.braunschweig.scenario;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.facilities.FacilitiesWriter;
import org.matsim.facilities.MatsimFacilitiesReader;

/**
 * End-to-end check of {@link RunAdaptLargeFacilityAccess} (issue #465): which facilities move to a
 * high-capacity access link, that their activities follow, that the sample size scales the activity count,
 * and that the fallback bound fails the step.
 */
public class RunAdaptLargeFacilityAccessTest {
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void largeFacilitiesOnSmallLinksMoveAndTheirActivitiesFollow() throws Exception {
		Path directory = writeScenario(new Coord(0.0, 0.0));
		run(directory, 0.01, 0.0);

		ActivityFacilities facilities = readFacilities(directory);
		// 10 activities in a 1 % sample stand for 1000: large, on a 600 veh/h street -> moved.
		assertEquals("secondary", facilities.getFacilities().get(Id.create("work_big", ActivityFacility.class))
				.getLinkId().toString());
		// 9 activities stand for 900: below the 1000 threshold, untouched.
		assertEquals("side_street", linkOf(facilities, "work_small"));
		// Large, but already on a link with enough capacity.
		assertEquals("secondary", linkOf(facilities, "work_on_main_road"));
		// Large, but a cordon portal: excluded by prefix.
		assertEquals("side_street", linkOf(facilities, "outside_1"));

		Population population = readPopulation(directory);
		Plan plan = population.getPersons().get(Id.createPersonId("big_0")).getSelectedPlan();
		assertEquals("home_link", ((Activity) plan.getPlanElements().get(0)).getLinkId().toString());
		assertEquals("secondary", ((Activity) plan.getPlanElements().get(2)).getLinkId().toString());
		// Legs that touch a moved activity lose their route (it starts or ends on the old link).
		assertNull(((Leg) plan.getPlanElements().get(1)).getRoute());
		assertEquals("side_street", ((Activity) population.getPersons().get(Id.createPersonId("small_0"))
				.getSelectedPlan().getPlanElements().get(2)).getLinkId().toString());

		List<String> summary = Files.readAllLines(directory.resolve("summary.csv"), StandardCharsets.UTF_8);
		assertEquals(RunAdaptLargeFacilityAccess.SUMMARY_HEADER, summary.get(0));
		assertEquals(List.of(
				"outside_1;10;1000.0;side_street;600.0;excluded;;;",
				"work_big;10;1000.0;side_street;600.0;moved;secondary;1000.0;200.0",
				"work_on_main_road;10;1000.0;secondary;1000.0;adequate;;;"), summary.subList(1, summary.size()));
	}

	@Test
	public void fallbackShareAboveTheBoundFailsTheStep() throws Exception {
		// The facility lies 2 km from the secondary road: no eligible link within 500 m.
		Path directory = writeScenario(new Coord(0.0, 2000.0));
		assertThrows(IllegalStateException.class, () -> run(directory, 0.01, 0.5));
	}

	@Test
	public void fallbackWithinTheBoundKeepsTheOldLink() throws Exception {
		Path directory = writeScenario(new Coord(0.0, 2000.0));
		run(directory, 0.01, 1.0);
		assertEquals("side_street", linkOf(readFacilities(directory), "work_big"));
		List<String> summary = Files.readAllLines(directory.resolve("summary.csv"), StandardCharsets.UTF_8);
		assertEquals("work_big;10;1000.0;side_street;600.0;fallback;;;", summary.get(2));
	}

	@Test
	public void invalidSampleSizeIsRejected() throws Exception {
		Path directory = writeScenario(new Coord(0.0, 0.0));
		assertThrows(IllegalArgumentException.class, () -> run(directory, 0.0, 0.0));
		assertThrows(IllegalArgumentException.class, () -> run(directory, 1.5, 0.0));
	}

	private Path writeScenario(Coord bigFacilityCoord) throws Exception {
		Path directory = temporaryFolder.newFolder().toPath();
		Network network = NetworkUtils.createNetwork();
		LargeFacilityAccessLinkSelectorTest.addLink(network, "side_street", -100.0, 10.0, 100.0, 10.0, 600.0,
				"tertiary");
		LargeFacilityAccessLinkSelectorTest.addLink(network, "secondary", -100.0, 200.0, 100.0, 200.0, 1000.0,
				"secondary");
		LargeFacilityAccessLinkSelectorTest.addLink(network, "home_link", 5000.0, 0.0, 5100.0, 0.0, 600.0,
				"residential");
		new NetworkWriter(network).write(directory.resolve("network.xml").toString());

		ActivityFacilities facilities = FacilitiesUtils.createActivityFacilities();
		addFacility(facilities, "work_big", bigFacilityCoord, "side_street");
		addFacility(facilities, "work_small", new Coord(10.0, 0.0), "side_street");
		addFacility(facilities, "work_on_main_road", new Coord(0.0, 190.0), "secondary");
		addFacility(facilities, "outside_1", new Coord(-10.0, 0.0), "side_street");
		Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
		addPersons(population, "big", "work_big", 10, facilities);
		addPersons(population, "small", "work_small", 9, facilities);
		addPersons(population, "main", "work_on_main_road", 10, facilities);
		addPersons(population, "portal", "outside_1", 10, facilities);
		new FacilitiesWriter(facilities).write(directory.resolve("facilities.xml").toString());
		new PopulationWriter(population).write(directory.resolve("population.xml").toString());
		return directory;
	}

	private static void addFacility(ActivityFacilities facilities, String id, Coord coord, String linkId) {
		ActivityFacility facility = facilities.getFactory().createActivityFacility(
				Id.create(id, ActivityFacility.class), coord, Id.createLinkId(linkId));
		facilities.addActivityFacility(facility);
	}

	/** Persons home -> work: one activity at the work facility each. */
	private static void addPersons(Population population, String prefix, String workFacility, int count,
			ActivityFacilities facilities) {
		PopulationFactory factory = population.getFactory();
		for (int index = 0; index < count; index++) {
			Person person = factory.createPerson(Id.createPersonId(prefix + "_" + index));
			Plan plan = factory.createPlan();
			// One home per person keeps the homes small (below the threshold).
			String homeId = "home_" + person.getId();
			addFacility(facilities, homeId, new Coord(5050.0, 5.0), "home_link");
			Activity home = factory.createActivityFromActivityFacilityId("home",
					Id.create(homeId, ActivityFacility.class));
			home.setLinkId(Id.createLinkId("home_link"));
			home.setEndTime(8 * 3600.0);
			plan.addActivity(home);
			Leg leg = factory.createLeg(TransportMode.car);
			ActivityFacility work = facilities.getFacilities().get(Id.create(workFacility, ActivityFacility.class));
			leg.setRoute(RouteUtils.createGenericRouteImpl(Id.createLinkId("home_link"), work.getLinkId()));
			plan.addLeg(leg);
			Activity workActivity = factory.createActivityFromActivityFacilityId("work", work.getId());
			workActivity.setLinkId(work.getLinkId());
			plan.addActivity(workActivity);
			person.addPlan(plan);
			population.addPerson(person);
		}
	}

	private static void run(Path directory, double sampleSize, double maximumFallbackShare) throws Exception {
		RunAdaptLargeFacilityAccess.main(new String[] {
				"--network-path", directory.resolve("network.xml").toString(),
				"--input-facilities-path", directory.resolve("facilities.xml").toString(),
				"--input-population-path", directory.resolve("population.xml").toString(),
				"--output-facilities-path", directory.resolve("facilities.xml").toString(),
				"--output-population-path", directory.resolve("population.xml").toString(),
				"--summary-path", directory.resolve("summary.csv").toString(),
				"--sample-size", Double.toString(sampleSize),
				"--minimum-activities", "1000",
				"--minimum-capacity-veh-per-h", "1000",
				"--maximum-distance-m", "500",
				"--maximum-fallback-share", Double.toString(maximumFallbackShare),
				"--excluded-facility-prefixes", "outside_" });
	}

	private static ActivityFacilities readFacilities(Path directory) {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new MatsimFacilitiesReader(scenario).readFile(directory.resolve("facilities.xml").toString());
		return scenario.getActivityFacilities();
	}

	private static String linkOf(ActivityFacilities facilities, String id) {
		return facilities.getFacilities().get(Id.create(id, ActivityFacility.class)).getLinkId().toString();
	}

	private static Population readPopulation(Path directory) {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new PopulationReader(scenario).readFile(directory.resolve("population.xml").toString());
		return scenario.getPopulation();
	}
}
