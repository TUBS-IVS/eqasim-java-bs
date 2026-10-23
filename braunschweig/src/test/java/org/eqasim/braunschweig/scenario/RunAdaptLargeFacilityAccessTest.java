package org.eqasim.braunschweig.scenario;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
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
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.facilities.FacilitiesWriter;
import org.matsim.facilities.MatsimFacilitiesReader;

/**
 * End-to-end check of {@link RunAdaptLargeFacilityAccess} (issue #465, ADR-0165): files in, files out, the two
 * summaries, and the bounds that stop a broken lots file or a broken network from passing silently.
 */
public class RunAdaptLargeFacilityAccessTest {
	private static final String LOTS_HEADER = "lot_id;x;y;spaces;capacity_source;access;parking;name";
	/** The layout sits at EPSG:25832-like coordinates, so WGS84 degrees fall far outside it. */
	private static final double X0 = 600000.0;
	private static final double Y0 = 5800000.0;

	private static void link(Network network, String id, double x0, double y0, double x1, double y1, double capacity,
			String highway) {
		LargeFacilityAccessLinkSelectorTest.addLink(network, id, X0 + x0, Y0 + y0, X0 + x1, Y0 + y1, capacity, highway);
	}

	/** A lot row at (x, y) relative to the layout origin. */
	static String lot(String id, double x, double y, int spaces) {
		return id + ";" + (X0 + x) + ";" + (Y0 + y) + ";" + spaces + ";area;;surface;" + id;
	}

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	/** A work site at (0, 0) with 10 persons, a shop site at (-1000, 0) with 10 persons, lots as given. */
	private Path scenario(List<String> lotRows) throws Exception {
		return scenario(lotRows, 0.0);
	}

	/** As above, with the shop site moved {@code shopNorthMeters} north (far from every 1000 veh/h link). */
	private Path scenario(List<String> lotRows, double shopNorthMeters) throws Exception {
		Path directory = temporaryFolder.newFolder().toPath();
		Network network = NetworkUtils.createNetwork();
		link(network, "side_street", -100.0, 10.0, 100.0, 10.0, 600.0, "tertiary");
		link(network, "lot_a_street", -100.0, 300.0, 100.0, 300.0, 1000.0,
				"secondary");
		link(network, "lot_b_street", -100.0, -210.0, 100.0, -210.0, 600.0,
				"residential");
		link(network, "shop_street", -1100.0, 10.0, -900.0, 10.0, 600.0,
				"tertiary");
		link(network, "shop_primary", -1100.0, 400.0, -900.0, 400.0, 1500.0,
				"primary");
		link(network, "home_link", 5000.0, 0.0, 5100.0, 0.0, 600.0, "residential");
		new NetworkWriter(network).write(directory.resolve("network.xml").toString());

		ActivityFacilities facilities = FacilitiesUtils.createActivityFacilities();
		Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
		addFacility(facilities, "work_a", new Coord(X0, Y0), "side_street", "work");
		addFacility(facilities, "sec_b_mall", new Coord(X0 - 1000.0, Y0 + shopNorthMeters), "shop_street", "shop");
		for (int index = 0; index < 10; index++) {
			addPerson(population, facilities, "w" + index, "work_a", "work");
			addPerson(population, facilities, "s" + index, "sec_b_mall", "shop");
		}
		new FacilitiesWriter(facilities).write(directory.resolve("facilities.xml").toString());
		new PopulationWriter(population).write(directory.resolve("population.xml").toString());

		List<String> lines = new ArrayList<>(List.of(LOTS_HEADER));
		lines.addAll(lotRows);
		Files.writeString(directory.resolve("lots.csv"), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
		return directory;
	}

	private static void addFacility(ActivityFacilities facilities, String id, Coord coord, String linkId, String type) {
		ActivityFacility facility = facilities.getFactory().createActivityFacility(Id.create(id, ActivityFacility.class),
				coord, Id.createLinkId(linkId));
		facility.addActivityOption(facilities.getFactory().createActivityOption(type));
		facilities.addActivityFacility(facility);
	}

	private static void addPerson(Population population, ActivityFacilities facilities, String personId, String facilityId,
			String type) {
		PopulationFactory factory = population.getFactory();
		String homeId = "home_" + personId;
		addFacility(facilities, homeId, new Coord(X0 + 5050.0, Y0 + 5.0), "home_link", "home");
		Person person = factory.createPerson(Id.createPersonId(personId));
		Plan plan = factory.createPlan();
		Activity home = factory.createActivityFromActivityFacilityId("home", Id.create(homeId, ActivityFacility.class));
		home.setLinkId(Id.createLinkId("home_link"));
		home.setEndTime(8 * 3600.0);
		plan.addActivity(home);
		plan.addLeg(factory.createLeg(TransportMode.car));
		ActivityFacility facility = facilities.getFacilities().get(Id.create(facilityId, ActivityFacility.class));
		Activity activity = factory.createActivityFromActivityFacilityId(type, facility.getId());
		activity.setLinkId(facility.getLinkId());
		plan.addActivity(activity);
		person.addPlan(plan);
		population.addPerson(person);
	}

	private static void run(Path directory, double maximumNoLotShare, double maximumFallbackShare, double sampleSize)
			throws Exception {
		RunAdaptLargeFacilityAccess.main(new String[] {
				"--network-path", directory.resolve("network.xml").toString(),
				"--input-facilities-path", directory.resolve("facilities.xml").toString(),
				"--input-population-path", directory.resolve("population.xml").toString(),
				"--output-facilities-path", directory.resolve("facilities.xml").toString(),
				"--output-population-path", directory.resolve("population.xml").toString(),
				"--parking-lots-path", directory.resolve("lots.csv").toString(),
				"--summary-path", directory.resolve("summary.csv").toString(),
				"--groups-path", directory.resolve("groups.csv").toString(),
				"--sample-size", Double.toString(sampleSize),
				"--minimum-activities", "1000",
				"--minimum-capacity-veh-per-h", "1000",
				"--maximum-distance-m", "1000",
				"--maximum-fallback-share", Double.toString(maximumFallbackShare),
				"--excluded-facility-prefixes", "outside_,EXT,home_",
				"--parking-demand-share", "0.5",
				"--parking-maximum-distance-m", "1500",
				"--lot-link-maximum-distance-m", "300",
				"--maximum-no-lot-share", Double.toString(maximumNoLotShare),
				"--scope-work-share", "0.5",
				"--scope-facility-prefixes", "uni_loc_" });
	}

	/** Rows of a semicolon CSV as maps keyed by the header. */
	private static List<Map<String, String>> rows(Path path, String expectedHeader) throws Exception {
		List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		assertEquals(expectedHeader, lines.get(0));
		List<String> header = Arrays.asList(lines.get(0).split(";", -1));
		List<Map<String, String>> rows = new ArrayList<>();
		for (String line : lines.subList(1, lines.size())) {
			String[] fields = line.split(";", -1);
			Map<String, String> row = new HashMap<>();
			for (int index = 0; index < header.size(); index++) {
				row.put(header.get(index), fields[index]);
			}
			rows.add(row);
		}
		return rows;
	}

	@Test
	public void aWorkSiteIsDistributedAndAShopSiteMovedEndToEnd() throws Exception {
		Path directory = scenario(List.of(lot("way/a", 0.0, 100.0, 300), lot("way/b", 0.0, -200.0, 200)));
		run(directory, 0.10, 0.15, 0.01);

		List<Map<String, String>> sites = rows(directory.resolve("summary.csv"), RunAdaptLargeFacilityAccess.SUMMARY_HEADER);
		assertEquals(2, sites.size());
		Map<String, String> shop = sites.get(0); // site keys sort "599000.00_5800000.00" before "600000.00_5800000.00"
		Map<String, String> work = sites.get(1);
		assertEquals("moved", shop.get("outcome"));
		assertEquals("false", shop.get("in_scope"));
		assertEquals("shop_primary", shop.get("new_link_id"));
		assertEquals("distributed", work.get("outcome"));
		assertEquals("true", work.get("in_scope"));
		assertEquals("2", work.get("access_groups"));
		assertEquals("500", work.get("spaces_used"));

		List<Map<String, String>> groups = rows(directory.resolve("groups.csv"), RunAdaptLargeFacilityAccess.GROUPS_HEADER);
		assertEquals(2, groups.size());
		assertEquals("lot_a_street", groups.get(0).get("link_id"));
		assertEquals("6", groups.get(0).get("persons"));
		assertEquals("way/a", groups.get(0).get("lot_ids"));
		assertEquals("work_a__access_2", groups.get(1).get("facility_ids"));

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new MatsimFacilitiesReader(scenario).readFile(directory.resolve("facilities.xml").toString());
		new PopulationReader(scenario).readFile(directory.resolve("population.xml").toString());
		ActivityFacility sub = scenario.getActivityFacilities().getFacilities()
				.get(Id.create("work_a__access_2", ActivityFacility.class));
		assertNotNull(sub);
		assertEquals("work_a", sub.getAttributes().getAttribute(LargeSiteAccessAssignment.PARENT_ATTRIBUTE));
		int onSub = 0;
		for (int index = 0; index < 10; index++) {
			Activity activity = (Activity) scenario.getPopulation().getPersons().get(Id.createPersonId("w" + index))
					.getSelectedPlan().getPlanElements().get(2);
			onSub += activity.getFacilityId().toString().equals("work_a__access_2") ? 1 : 0;
		}
		assertEquals(4, onSub);
	}

	@Test
	public void lotsInAnotherCrsFailTheStep() throws Exception {
		// WGS84 degrees instead of EPSG:25832 metres: every lot lies outside the network extent grown by 2 km.
		Path directory = scenario(List.of("way/a;10.52;52.26;300;area;;surface;A"));
		IllegalStateException error = assertThrows(IllegalStateException.class, () -> run(directory, 1.0, 1.0, 0.01));
		assertTrue(error.getMessage(), error.getMessage().contains("network"));
	}

	@Test
	public void inScopeSitesWithoutLotsAboveTheBoundFailTheStep() throws Exception {
		// The only lot lies 3 km from the work site: inside the network extent + 2 km, beyond the 1500 m radius.
		Path far = scenario(List.of(lot("way/far", 3000.0, 0.0, 300)));
		assertThrows(IllegalStateException.class, () -> run(far, 0.10, 1.0, 0.01));
		Path tolerated = scenario(List.of(lot("way/far", 3000.0, 0.0, 300)));
		run(tolerated, 1.0, 1.0, 0.01);
		assertEquals("moved", rows(tolerated.resolve("summary.csv"), RunAdaptLargeFacilityAccess.SUMMARY_HEADER).get(1)
				.get("outcome"));
	}

	@Test
	public void singleLinkFallbacksAboveTheBoundFailTheStep() throws Exception {
		// The shop site lies 5 km north of the only 1000 veh/h links: no eligible link within 1000 m, a fallback.
		Path strict = scenario(List.of(lot("way/a", 0.0, 100.0, 300), lot("way/b", 0.0, -200.0, 200)), 5000.0);
		assertThrows(IllegalStateException.class, () -> run(strict, 1.0, 0.1, 0.01));
		Path tolerated = scenario(List.of(lot("way/a", 0.0, 100.0, 300), lot("way/b", 0.0, -200.0, 200)), 5000.0);
		run(tolerated, 1.0, 1.0, 0.01);
		assertEquals("fallback", rows(tolerated.resolve("summary.csv"), RunAdaptLargeFacilityAccess.SUMMARY_HEADER).get(0)
				.get("outcome"));
	}

	@Test
	public void invalidOptionsAreRejected() throws Exception {
		Path directory = scenario(List.of(lot("way/a", 0.0, 100.0, 300)));
		assertThrows(IllegalArgumentException.class, () -> run(directory, 0.10, 0.15, 0.0));
		assertThrows(IllegalArgumentException.class, () -> run(directory, 1.5, 0.15, 0.01));
		assertThrows(IllegalArgumentException.class, () -> run(directory, 0.10, -0.1, 0.01));
	}
}
