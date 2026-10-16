package org.eqasim.braunschweig.simwrapper;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.eqasim.braunschweig.simwrapper.SimWrapperNetworkAttributeSanitizer.Outcome;
import org.eqasim.braunschweig.simwrapper.SimWrapperNetworkAttributeSanitizer.Result;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.application.ApplicationUtils;
import org.matsim.application.CommandRunner;
import org.matsim.application.prepare.network.CreateAvroNetwork;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup.CompressionType;
import org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ProjectionUtils;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * SimWrapper's OverviewDashboard runs {@code CreateAvroNetwork --with-properties} on the network it finds in the run
 * directory. Avro field names must match [A-Za-z_][A-Za-z0-9_]*, but networks converted from OSM by pt2matsim carry
 * link attributes such as {@code osm:way:highway}, so the command fails with a SchemaParseException. The sanitizer
 * writes a copy of the MATSim output network with legal attribute names, under the name the SimWrapper lookup
 * prefers, and leaves the MATSim output network itself untouched.
 */
public class SimWrapperNetworkAttributeSanitizerTest {
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	private Path runDirectory() throws IOException {
		Path directory = temporaryFolder.getRoot().toPath().resolve("simulation_output");
		Files.createDirectories(directory);
		return directory;
	}

	/** Two nodes and two links with pt2matsim-style attributes, one legal attribute and one node attribute. */
	private Network createNetwork() {
		Network network = NetworkUtils.createNetwork();
		ProjectionUtils.putCRS(network, "EPSG:25832");

		Node from = network.getFactory().createNode(Id.createNodeId("n1"), new Coord(600000, 5800000));
		Node to = network.getFactory().createNode(Id.createNodeId("n2"), new Coord(601000, 5800000));
		from.getAttributes().putAttribute("osm:node:id", 4711L);
		network.addNode(from);
		network.addNode(to);

		Link forward = network.getFactory().createLink(Id.createLinkId("l1"), from, to);
		forward.setLength(1000.0);
		forward.setFreespeed(13.9);
		forward.setCapacity(1800.0);
		forward.setNumberOfLanes(1.0);
		forward.setAllowedModes(Set.of(TransportMode.car));
		forward.getAttributes().putAttribute("osm:way:highway", "primary");
		forward.getAttributes().putAttribute("osm:way:id", 123456L);
		forward.getAttributes().putAttribute("osm:way:oneway", "yes");
		forward.getAttributes().putAttribute("type", "primary");
		network.addLink(forward);

		Link backward = network.getFactory().createLink(Id.createLinkId("l2"), to, from);
		backward.setLength(1000.0);
		backward.setFreespeed(13.9);
		backward.setCapacity(1800.0);
		backward.setNumberOfLanes(1.0);
		backward.setAllowedModes(Set.of(TransportMode.car));
		backward.getAttributes().putAttribute("osm:way:highway", "primary");
		backward.getAttributes().putAttribute("type", "primary");
		network.addLink(backward);
		return network;
	}

	private Path writeOutputNetwork(Path runDirectory, Network network) {
		Path path = runDirectory.resolve("output_network.xml.gz");
		NetworkUtils.writeNetwork(network, path.toString());
		return path;
	}

	/** Runs the command the way SimWrapper does: input network resolved from the run directory. */
	private boolean runCreateAvroNetworkWithProperties(Path runDirectory) throws IOException {
		Path analysisOutput = temporaryFolder.getRoot().toPath().resolve("analysis_" + System.nanoTime());
		CommandRunner runner = new CommandRunner();
		runner.setOutput(analysisOutput);
		runner.add(CreateAvroNetwork.class, "--with-properties");
		runner.run(runDirectory);

		// The runner logs a failing command instead of throwing; the Avro file exists only after a success.
		if (!Files.exists(analysisOutput)) {
			return false;
		}

		try (var files = Files.walk(analysisOutput)) {
			return files.anyMatch(path -> path.getFileName().toString().equals("network.avro")
					&& path.toFile().length() > 0);
		}
	}

	@Test
	public void withoutSanitizingTheRealCommandFailsOnTheOsmAttributeNames() throws IOException {
		Path runDirectory = runDirectory();
		writeOutputNetwork(runDirectory, createNetwork());

		// Documents the upstream behaviour that the sanitizer works around.
		assertFalse(runCreateAvroNetworkWithProperties(runDirectory));
	}

	@Test
	public void afterSanitizingTheRealCommandSucceeds() throws IOException {
		Path runDirectory = runDirectory();
		Path source = writeOutputNetwork(runDirectory, createNetwork());

		Result result = SimWrapperNetworkAttributeSanitizer.sanitize(source, runDirectory.resolve("network.xml.gz"));

		assertEquals(Outcome.WRITTEN, result.outcome());
		assertTrue(runCreateAvroNetworkWithProperties(runDirectory));
	}

	/** The server failure: only link attributes (osm:way:*) are illegal, nodes carry none. */
	@Test
	public void linkOnlyIllegalAttributesFailBeforeAndSucceedAfterSanitizing() throws IOException {
		Path runDirectory = runDirectory();
		Network network = createNetwork();
		network.getNodes().get(Id.createNodeId("n1")).getAttributes().removeAttribute("osm:node:id");
		Path source = writeOutputNetwork(runDirectory, network);

		assertFalse(runCreateAvroNetworkWithProperties(runDirectory));

		Result result = SimWrapperNetworkAttributeSanitizer.sanitize(source, runDirectory.resolve("network.xml.gz"));

		assertEquals(Outcome.WRITTEN, result.outcome());
		assertTrue(result.renamedNodeAttributes().isEmpty());
		assertTrue(runCreateAvroNetworkWithProperties(runDirectory));
	}

	@Test
	public void theSanitizedNetworkIsWhatTheSimWrapperLookupResolves() throws IOException {
		Path runDirectory = runDirectory();
		Path source = writeOutputNetwork(runDirectory, createNetwork());
		assertEquals(source, ApplicationUtils.matchInput("network.xml", runDirectory));

		SimWrapperNetworkAttributeSanitizer.sanitize(source, runDirectory.resolve("network.xml.gz"));

		assertEquals(runDirectory.resolve("network.xml.gz"), ApplicationUtils.matchInput("network.xml", runDirectory));
	}

	@Test
	public void theMatsimOutputNetworkIsNeverModified() throws IOException {
		Path runDirectory = runDirectory();
		Path source = writeOutputNetwork(runDirectory, createNetwork());
		byte[] before = Files.readAllBytes(source);

		SimWrapperNetworkAttributeSanitizer.sanitize(source, runDirectory.resolve("network.xml.gz"));

		assertArrayEquals(before, Files.readAllBytes(source));
	}

	@Test
	public void renamedAttributesKeepTheirValuesAndEverythingElseIsPreserved() throws IOException {
		Path runDirectory = runDirectory();
		Network original = createNetwork();
		Path source = writeOutputNetwork(runDirectory, original);
		Path target = runDirectory.resolve("network.xml.gz");

		SimWrapperNetworkAttributeSanitizer.sanitize(source, target);

		Network sanitized = NetworkUtils.readNetwork(target.toString());
		assertEquals(2, sanitized.getNodes().size());
		assertEquals(2, sanitized.getLinks().size());
		assertEquals("EPSG:25832", ProjectionUtils.getCRS(sanitized));

		Link link = sanitized.getLinks().get(Id.createLinkId("l1"));
		assertEquals(1000.0, link.getLength(), 0.0);
		assertEquals(13.9, link.getFreespeed(), 0.0);
		assertEquals(Set.of(TransportMode.car), link.getAllowedModes());
		assertEquals("primary", link.getAttributes().getAttribute("osm_way_highway"));
		assertEquals("yes", link.getAttributes().getAttribute("osm_way_oneway"));
		assertEquals(123456L, ((Number) link.getAttributes().getAttribute("osm_way_id")).longValue());
		assertEquals("primary", link.getAttributes().getAttribute("type"));
		assertNull(link.getAttributes().getAttribute("osm:way:highway"));

		Node node = sanitized.getNodes().get(Id.createNodeId("n1"));
		assertEquals(4711L, ((Number) node.getAttributes().getAttribute("osm_node_id")).longValue());
		assertNull(node.getAttributes().getAttribute("osm:node:id"));
	}

	@Test
	public void theResultReportsEveryRenamedNameAndTheAffectedElementCounts() throws IOException {
		Path runDirectory = runDirectory();
		Path source = writeOutputNetwork(runDirectory, createNetwork());

		Result result = SimWrapperNetworkAttributeSanitizer.sanitize(source, runDirectory.resolve("network.xml.gz"));

		assertEquals(Map.of("osm:way:highway", "osm_way_highway", "osm:way:id", "osm_way_id", "osm:way:oneway",
				"osm_way_oneway"), result.renamedLinkAttributes());
		assertEquals(Map.of("osm:node:id", "osm_node_id"), result.renamedNodeAttributes());
		assertEquals(2, result.affectedLinks());
		assertEquals(1, result.affectedNodes());
	}

	@Test
	public void aNetworkWithLegalNamesOnlyIsLeftAloneAndNoFileIsWritten() throws IOException {
		Path runDirectory = runDirectory();
		Network network = createNetwork();
		for (Link link : network.getLinks().values()) {
			link.getAttributes().removeAttribute("osm:way:highway");
			link.getAttributes().removeAttribute("osm:way:id");
			link.getAttributes().removeAttribute("osm:way:oneway");
		}
		network.getNodes().get(Id.createNodeId("n1")).getAttributes().removeAttribute("osm:node:id");
		Path source = writeOutputNetwork(runDirectory, network);
		Path target = runDirectory.resolve("network.xml.gz");

		Result result = SimWrapperNetworkAttributeSanitizer.sanitize(source, target);

		assertEquals(Outcome.NOTHING_TO_RENAME, result.outcome());
		assertFalse(Files.exists(target));
		assertTrue(runCreateAvroNetworkWithProperties(runDirectory));
	}

	@Test
	public void missingSourceNetworkIsReportedAndNothingIsWritten() throws IOException {
		Path runDirectory = runDirectory();
		Path target = runDirectory.resolve("network.xml.gz");

		Result result = SimWrapperNetworkAttributeSanitizer.sanitize(runDirectory.resolve("output_network.xml.gz"),
				target);

		assertEquals(Outcome.SOURCE_MISSING, result.outcome());
		assertFalse(Files.exists(target));
	}

	@Test
	public void aStaleFileUnderTheTargetNameIsReplaced() throws IOException {
		Path runDirectory = runDirectory();
		Path source = writeOutputNetwork(runDirectory, createNetwork());
		Path target = runDirectory.resolve("network.xml.gz");
		Files.writeString(target, "stale");

		SimWrapperNetworkAttributeSanitizer.sanitize(source, target);

		assertEquals(2, NetworkUtils.readNetwork(target.toString()).getLinks().size());
	}

	@Test
	public void nameSanitizingFollowsTheAvroNameGrammar() {
		assertEquals("osm_way_highway", SimWrapperNetworkAttributeSanitizer.sanitizeName("osm:way:highway"));
		assertEquals("a_b", SimWrapperNetworkAttributeSanitizer.sanitizeName("a-b"));
		assertEquals("_1abc", SimWrapperNetworkAttributeSanitizer.sanitizeName("1abc"));
		assertEquals("a_b_c", SimWrapperNetworkAttributeSanitizer.sanitizeName("a b.c"));
		assertEquals("legal_Name1", SimWrapperNetworkAttributeSanitizer.sanitizeName("legal_Name1"));
		assertEquals("_", SimWrapperNetworkAttributeSanitizer.sanitizeName(":"));
	}

	@Test
	public void renamedNamesNeverCollideWithExistingOrOtherRenamedNames() throws IOException {
		Path runDirectory = runDirectory();
		Network network = createNetwork();
		Link link = network.getLinks().get(Id.createLinkId("l1"));
		// "osm_way_highway" is already a legal attribute; "osm:way:highway" and "osm.way.highway" both map onto it.
		link.getAttributes().putAttribute("osm_way_highway", "legal-original");
		link.getAttributes().putAttribute("osm.way.highway", "dot-variant");
		Path source = writeOutputNetwork(runDirectory, network);
		Path target = runDirectory.resolve("network.xml.gz");

		Result result = SimWrapperNetworkAttributeSanitizer.sanitize(source, target);

		Map<String, String> renamed = result.renamedLinkAttributes();
		assertNotEquals(renamed.get("osm:way:highway"), renamed.get("osm.way.highway"));
		assertFalse(renamed.containsValue("osm_way_highway"));
		assertEquals(renamed.size(), Set.copyOf(renamed.values()).size());

		Link sanitized = NetworkUtils.readNetwork(target.toString()).getLinks().get(Id.createLinkId("l1"));
		assertEquals("legal-original", sanitized.getAttributes().getAttribute("osm_way_highway"));
		assertEquals("primary", sanitized.getAttributes().getAttribute(renamed.get("osm:way:highway")));
		assertEquals("dot-variant", sanitized.getAttributes().getAttribute(renamed.get("osm.way.highway")));
		assertTrue(runCreateAvroNetworkWithProperties(runDirectory));
	}

	@Test
	public void listenerWritesTheSanitizedNetworkAtShutdown() throws IOException {
		Path root = temporaryFolder.getRoot().toPath().resolve("listener_output");
		OutputDirectoryHierarchy hierarchy = new OutputDirectoryHierarchy(root.toString(), null,
				OverwriteFileSetting.deleteDirectoryIfExists, CompressionType.gzip);
		Config config = ConfigUtils.createConfig();
		config.controller().setCompressionType(CompressionType.gzip);
		Files.createDirectories(root);
		writeOutputNetwork(root, createNetwork());

		new SimWrapperNetworkAttributeSanitizer(config, hierarchy)
				.notifyShutdown(new ShutdownEvent(null, false, 0, null));

		assertTrue(Files.exists(root.resolve("network.xml.gz")));
	}

	@Test
	public void listenerDoesNothingAfterAnUnexpectedTermination() throws IOException {
		Path root = temporaryFolder.getRoot().toPath().resolve("listener_output");
		OutputDirectoryHierarchy hierarchy = new OutputDirectoryHierarchy(root.toString(), null,
				OverwriteFileSetting.deleteDirectoryIfExists, CompressionType.gzip);
		Config config = ConfigUtils.createConfig();
		Files.createDirectories(root);
		writeOutputNetwork(root, createNetwork());

		new SimWrapperNetworkAttributeSanitizer(config, hierarchy)
				.notifyShutdown(new ShutdownEvent(null, true, 0, null));

		assertFalse(Files.exists(root.resolve("network.xml.gz")));
	}

	@Test
	public void listenerRunsAfterMatsimWritesItsOutputsAndBeforeSimWrapper() {
		double priority = new SimWrapperNetworkAttributeSanitizer(ConfigUtils.createConfig(), null).priority();

		// DumpDataAtEnd (0.0) writes output_network.xml.gz, the SimWrapperListener (-1000.0) runs the analyses.
		assertTrue("priority must be below 0.0, was " + priority, priority < 0.0);
		assertTrue("priority must be above -1000.0, was " + priority, priority > -1000.0);
	}

	/** A real controller run: the sanitized network must exist when the SimWrapper-priority listener runs. */
	@Test
	public void inARealControllerRunTheSanitizedNetworkExistsWhenTheSimWrapperPriorityListenerRuns()
			throws IOException {
		Path root = temporaryFolder.getRoot().toPath().resolve("controller_output");
		Config config = ConfigUtils.createConfig();
		config.controller().setOutputDirectory(root.toString());
		config.controller().setLastIteration(0);
		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setCompressionType(CompressionType.gzip);
		config.controller().setCreateGraphs(false);
		ActivityParams activityParams = new ActivityParams("h");
		activityParams.setTypicalDuration(3600.0);
		config.scoring().addActivityParams(activityParams);

		Scenario scenario = ScenarioUtils.createScenario(config);
		Network source = createNetwork();
		Network target = scenario.getNetwork();
		for (Node node : source.getNodes().values()) {
			Node copy = target.getFactory().createNode(node.getId(), node.getCoord());
			node.getAttributes().getAsMap().forEach((name, value) -> copy.getAttributes().putAttribute(name, value));
			target.addNode(copy);
		}
		for (Link link : source.getLinks().values()) {
			Link copy = target.getFactory().createLink(link.getId(), target.getNodes().get(link.getFromNode().getId()),
					target.getNodes().get(link.getToNode().getId()));
			copy.setLength(link.getLength());
			copy.setFreespeed(link.getFreespeed());
			copy.setCapacity(link.getCapacity());
			copy.setNumberOfLanes(link.getNumberOfLanes());
			copy.setAllowedModes(link.getAllowedModes());
			link.getAttributes().getAsMap().forEach((name, value) -> copy.getAttributes().putAttribute(name, value));
			target.addLink(copy);
		}
		ProjectionUtils.putCRS(target, "EPSG:25832");

		Path[] resolvedAtSimWrapperPriority = new Path[1];
		Controler controller = new Controler(scenario);
		controller.addOverridingModule(new SimWrapperAnalysisInputModule());
		controller.addControlerListener(new ShutdownListener() {
			@Override
			public double priority() {
				return -1000.0;
			}

			@Override
			public void notifyShutdown(ShutdownEvent event) {
				resolvedAtSimWrapperPriority[0] = ApplicationUtils.matchInput("network.xml", root);
			}
		});
		controller.run();

		assertEquals(root.resolve("network.xml.gz"), resolvedAtSimWrapperPriority[0]);
		assertTrue(Files.exists(root.resolve("output_network.xml.gz")));
	}
}
