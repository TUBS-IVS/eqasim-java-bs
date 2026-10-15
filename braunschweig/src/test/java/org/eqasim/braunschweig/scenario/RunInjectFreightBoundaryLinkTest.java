package org.eqasim.braunschweig.scenario;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

/**
 * End-to-end check of the boundary-link wiring in {@link RunInjectFreight} (issue #444): which endpoint of
 * which trip type gets an explicit link, and that the fallback bound fails the injection.
 */
public class RunInjectFreightBoundaryLinkTest {
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	/** Boundary crossing on the west edge; the region interior lies to the east. */
	private static final String GATE = "200.0;1.0";
	private static final String INSIDE = "50000.0;0.0";

	@Test
	public void boundaryEndpointsGetTheCarriagewayOfTheirTripDirection() throws Exception {
		Path configPath = writeScenario();
		run(configPath, List.of(
				"freight_incoming;" + GATE + ";" + INSIDE + ";3600;incoming",
				"freight_outgoing;" + INSIDE + ";" + GATE + ";3600;outgoing",
				"freight_transit;" + GATE + ";" + GATE + ";3600;transit",
				"freight_internal;" + GATE + ";" + INSIDE + ";3600;internal"), 0.5);

		Population population = readPopulation(configPath);
		assertEquals("a2_eastbound", firstLink(population, "freight_incoming"));
		assertNull(lastLink(population, "freight_incoming"));
		assertNull(firstLink(population, "freight_outgoing"));
		assertEquals("a2_westbound", lastLink(population, "freight_outgoing"));
		assertNull(firstLink(population, "freight_internal"));
		assertNull(lastLink(population, "freight_internal"));
		// Same start and end point: no travel direction, so both endpoints fall back to MATSim snapping.
		assertNull(firstLink(population, "freight_transit"));
		assertNull(lastLink(population, "freight_transit"));

		List<String> summary = Files.readAllLines(configPath.resolveSibling("boundary_links.csv"), StandardCharsets.UTF_8);
		assertEquals(List.of("endpoint_role;selected_link_endpoints;nearest_node_fallback_endpoints", "start;1;1", "end;1;1"),
				summary);
	}

	@Test
	public void unknownTripTypeIsRejected() throws Exception {
		Path configPath = writeScenario();
		assertThrows(IllegalStateException.class,
				() -> run(configPath, List.of("freight_x;" + GATE + ";" + INSIDE + ";3600;through"), 1.0));
	}

	@Test
	public void fallbackShareAboveTheBoundFailsTheInjection() throws Exception {
		Path configPath = writeScenario();
		assertThrows(IllegalStateException.class,
				() -> run(configPath, List.of("freight_far;5000.0;5000.0;" + INSIDE + ";3600;incoming"), 0.05));
	}

	private Path writeScenario() throws Exception {
		Path directory = temporaryFolder.newFolder().toPath();
		Network network = NetworkUtils.createNetwork();
		addNode(network, "west", 0.0, 0.0);
		addNode(network, "east", 400.0, 0.0);
		addNode(network, "east_return", 400.0, 20.0);
		addNode(network, "west_return", 0.0, 20.0);
		addNode(network, "residential_south", 190.0, -200.0);
		addNode(network, "residential_north", 195.0, -40.0);
		addLink(network, "a2_eastbound", "west", "east", 6000.0);
		addLink(network, "a2_westbound", "east_return", "west_return", 6000.0);
		addLink(network, "residential", "residential_south", "residential_north", 600.0);
		new NetworkWriter(network).write(directory.resolve("network.xml").toString());
		new PopulationWriter(PopulationUtils.createPopulation(ConfigUtils.createConfig()))
				.write(directory.resolve("population.xml").toString());
		// The vehicle schema requires at least one vehicle type.
		Vehicles vehicles = VehicleUtils.createVehiclesContainer();
		vehicles.addVehicleType(VehicleUtils.createVehicleType(Id.create(TransportMode.car, VehicleType.class)));
		new MatsimVehicleWriter(vehicles).writeFile(directory.resolve("vehicles.xml").toString());

		Config config = ConfigUtils.createConfig();
		config.network().setInputFile("network.xml");
		config.plans().setInputFile("population.xml");
		config.vehicles().setVehiclesFile("vehicles.xml");
		Path configPath = directory.resolve("config.xml");
		new ConfigWriter(config).write(configPath.toString());
		return configPath;
	}

	private static void run(Path configPath, List<String> rows, double maximumFallbackShare) throws Exception {
		Path csvPath = configPath.resolveSibling("freight.csv");
		StringBuilder csv = new StringBuilder(
				"person_id;origin_x;origin_y;destination_x;destination_y;departure_time;trip_type\n");
		rows.forEach(row -> csv.append(row).append('\n'));
		Files.writeString(csvPath, csv.toString(), StandardCharsets.UTF_8);
		RunInjectFreight.main(new String[] {
				"--config-path", configPath.toString(),
				"--freight-csv-path", csvPath.toString(),
				"--summary-path", configPath.resolveSibling("summary.csv").toString(),
				"--boundary-link-minimum-capacity-veh-per-h", "1000",
				"--boundary-link-maximum-distance-m", "300",
				"--boundary-link-maximum-fallback-share", Double.toString(maximumFallbackShare),
				"--boundary-link-summary-path", configPath.resolveSibling("boundary_links.csv").toString() });
	}

	private static Population readPopulation(Path configPath) {
		var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new PopulationReader(scenario).readFile(configPath.resolveSibling("population.xml").toString());
		return scenario.getPopulation();
	}

	private static String firstLink(Population population, String personId) {
		return linkOf((Activity) person(population, personId).getSelectedPlan().getPlanElements().get(0));
	}

	private static String lastLink(Population population, String personId) {
		var elements = person(population, personId).getSelectedPlan().getPlanElements();
		return linkOf((Activity) elements.get(elements.size() - 1));
	}

	private static Person person(Population population, String personId) {
		return population.getPersons().get(Id.createPersonId(personId));
	}

	private static String linkOf(Activity activity) {
		return activity.getLinkId() == null ? null : activity.getLinkId().toString();
	}

	private static void addNode(Network network, String id, double x, double y) {
		network.addNode(network.getFactory().createNode(Id.createNodeId(id), new Coord(x, y)));
	}

	private static void addLink(Network network, String id, String from, String to, double capacityVehiclesPerHour) {
		Node fromNode = network.getNodes().get(Id.createNodeId(from));
		Node toNode = network.getNodes().get(Id.createNodeId(to));
		Link link = network.getFactory().createLink(Id.createLinkId(id), fromNode, toNode);
		link.setAllowedModes(Set.of(TransportMode.car));
		link.setLength(NetworkUtils.getEuclideanDistance(fromNode.getCoord(), toNode.getCoord()));
		link.setCapacity(capacityVehiclesPerHour);
		link.setFreespeed(20.0);
		network.addLink(link);
	}
}
