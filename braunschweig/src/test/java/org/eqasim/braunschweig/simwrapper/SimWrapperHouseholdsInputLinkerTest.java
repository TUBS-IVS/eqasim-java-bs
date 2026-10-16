package org.eqasim.braunschweig.simwrapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eqasim.braunschweig.simwrapper.SimWrapperHouseholdsInputLinker.Outcome;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.config.groups.ControllerConfigGroup.CompressionType;
import org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.households.Household;
import org.matsim.households.HouseholdsWriterV10;

/**
 * MATSim's SimWrapper PublicTransitAnalysis loads a scenario from {@code output_config.xml}. It overrides the network,
 * transit and vehicle inputs and clears plans and facilities, but NOT the households input, so a relative households
 * path of the run config is resolved against the output directory, where MATSim only writes
 * {@code output_households.xml.gz}. The linker makes the configured name resolvable there.
 */
public class SimWrapperHouseholdsInputLinkerTest {
	private static final String CONFIGURED_NAME = "scenario_households.xml.gz";

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	private Path outputDirectory() throws IOException {
		Path directory = temporaryFolder.getRoot().toPath().resolve("simulation_output");
		Files.createDirectories(directory);
		return directory;
	}

	/** Writes one household with a known id to the MATSim output file name and returns its path. */
	private Path writeOutputHouseholds(Path outputDirectory) throws IOException {
		Files.createDirectories(outputDirectory);
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		Household household = scenario.getHouseholds().getFactory().createHousehold(Id.create("h1", Household.class));
		scenario.getHouseholds().getHouseholds().put(household.getId(), household);

		Path produced = outputDirectory.resolve("output_households.xml.gz");
		new HouseholdsWriterV10(scenario.getHouseholds()).writeFile(produced.toString());
		return produced;
	}

	/** Mimics PublicTransitAnalysis.prepareConfig for the inputs that matter here: output_config.xml, no plans. */
	private Scenario loadScenarioLikeTheAnalysis(Path outputDirectory, String configuredName) {
		Config runConfig = ConfigUtils.createConfig();
		runConfig.households().setInputFile(configuredName);
		new ConfigWriter(runConfig).write(outputDirectory.resolve("output_config.xml").toString());

		Config analysisConfig = ConfigUtils.loadConfig(outputDirectory.resolve("output_config.xml").toString());
		analysisConfig.plans().setInputFile(null);
		analysisConfig.facilities().setInputFile(null);
		return ScenarioUtils.loadScenario(analysisConfig);
	}

	@Test
	public void withoutTheLinkTheAnalysisStyleScenarioLoadFails() throws IOException {
		Path outputDirectory = outputDirectory();
		writeOutputHouseholds(outputDirectory);

		// Documents the upstream behaviour that the linker works around.
		assertThrows(UncheckedIOException.class, () -> loadScenarioLikeTheAnalysis(outputDirectory, CONFIGURED_NAME));
	}

	@Test
	public void afterLinkingTheAnalysisStyleScenarioLoadFindsTheHouseholds() throws IOException {
		Path outputDirectory = outputDirectory();
		Path produced = writeOutputHouseholds(outputDirectory);

		Outcome outcome = SimWrapperHouseholdsInputLinker.ensureHouseholdsInput(outputDirectory, CONFIGURED_NAME,
				produced);

		assertTrue(outcome == Outcome.LINKED || outcome == Outcome.COPIED);
		Scenario scenario = loadScenarioLikeTheAnalysis(outputDirectory, CONFIGURED_NAME);
		assertEquals(1, scenario.getHouseholds().getHouseholds().size());
	}

	@Test
	public void symbolicLinkIsRelativeSoTheOutputDirectoryStaysRelocatable() throws IOException {
		Path outputDirectory = outputDirectory();
		Path produced = writeOutputHouseholds(outputDirectory);

		Outcome outcome = SimWrapperHouseholdsInputLinker.ensureHouseholdsInput(outputDirectory, CONFIGURED_NAME,
				produced);

		Path link = outputDirectory.resolve(CONFIGURED_NAME);
		if (outcome == Outcome.LINKED) {
			assertTrue(Files.isSymbolicLink(link));
			assertFalse(Files.readSymbolicLink(link).isAbsolute());
		} else {
			// Windows without the symlink privilege: the explicit copy fallback must carry identical content.
			assertEquals(Outcome.COPIED, outcome);
			assertEquals(Files.size(produced), Files.size(link));
		}
	}

	@Test
	public void symbolicLinkTargetIsTheMatsimOutputFileRelativeToTheLinkDirectory() throws IOException {
		Path outputDirectory = outputDirectory();
		Path produced = writeOutputHouseholds(outputDirectory);
		Path[] recorded = new Path[2];

		Outcome outcome = SimWrapperHouseholdsInputLinker.ensureHouseholdsInput(outputDirectory,
				"input/scenario_households.xml.gz", produced, (link, target) -> {
					recorded[0] = link;
					recorded[1] = target;
				});

		assertEquals(Outcome.LINKED, outcome);
		assertEquals(outputDirectory.resolve("input/scenario_households.xml.gz").toAbsolutePath().normalize(),
				recorded[0]);
		// The link lives in input/, the MATSim file in the output root: the relative target climbs one level.
		assertEquals(Path.of("..", "output_households.xml.gz"), recorded[1]);
	}

	@Test
	public void copyIsUsedAndReportedWhenSymbolicLinksAreNotPossible() throws IOException {
		Path outputDirectory = outputDirectory();
		Path produced = writeOutputHouseholds(outputDirectory);

		Outcome outcome = SimWrapperHouseholdsInputLinker.ensureHouseholdsInput(outputDirectory, CONFIGURED_NAME,
				produced, (link, target) -> {
					throw new UnsupportedOperationException("no symbolic links in this test");
				});

		assertEquals(Outcome.COPIED, outcome);
		assertFalse(Files.isSymbolicLink(outputDirectory.resolve(CONFIGURED_NAME)));
		assertEquals(Files.size(produced), Files.size(outputDirectory.resolve(CONFIGURED_NAME)));
	}

	@Test
	public void existingFileUnderTheConfiguredNameIsNeverOverwritten() throws IOException {
		Path outputDirectory = outputDirectory();
		Path produced = writeOutputHouseholds(outputDirectory);
		Path existing = outputDirectory.resolve(CONFIGURED_NAME);
		Files.write(existing, "keep me".getBytes(StandardCharsets.UTF_8));

		Outcome outcome = SimWrapperHouseholdsInputLinker.ensureHouseholdsInput(outputDirectory, CONFIGURED_NAME,
				produced);

		assertEquals(Outcome.ALREADY_PRESENT, outcome);
		assertEquals("keep me", Files.readString(existing));
	}

	@Test
	public void missingMatsimOutputFileIsReportedAndNothingIsCreated() throws IOException {
		Path outputDirectory = outputDirectory();

		Outcome outcome = SimWrapperHouseholdsInputLinker.ensureHouseholdsInput(outputDirectory, CONFIGURED_NAME,
				outputDirectory.resolve("output_households.xml.gz"));

		assertEquals(Outcome.SOURCE_MISSING, outcome);
		assertFalse(Files.exists(outputDirectory.resolve(CONFIGURED_NAME)));
	}

	@Test
	public void unconfiguredHouseholdsInputNeedsNoLink() throws IOException {
		Path outputDirectory = outputDirectory();
		Path produced = writeOutputHouseholds(outputDirectory);

		assertEquals(Outcome.NOT_CONFIGURED,
				SimWrapperHouseholdsInputLinker.ensureHouseholdsInput(outputDirectory, null, produced));
		assertEquals(Outcome.NOT_CONFIGURED,
				SimWrapperHouseholdsInputLinker.ensureHouseholdsInput(outputDirectory, "  ", produced));
	}

	@Test
	public void absoluteConfiguredPathIsLeftToTheAnalysis() throws IOException {
		Path outputDirectory = outputDirectory();
		Path produced = writeOutputHouseholds(outputDirectory);
		Path absolute = temporaryFolder.getRoot().toPath().resolve("elsewhere_households.xml.gz").toAbsolutePath();

		assertEquals(Outcome.NOT_RELATIVE,
				SimWrapperHouseholdsInputLinker.ensureHouseholdsInput(outputDirectory, absolute.toString(), produced));
		assertFalse(Files.exists(absolute));
	}

	@Test
	public void relativePathLeavingTheOutputDirectoryIsRefused() throws IOException {
		Path outputDirectory = outputDirectory();
		Path produced = writeOutputHouseholds(outputDirectory);

		assertEquals(Outcome.OUTSIDE_OUTPUT_DIRECTORY, SimWrapperHouseholdsInputLinker
				.ensureHouseholdsInput(outputDirectory, "../escaped_households.xml.gz", produced));
		assertFalse(Files.exists(temporaryFolder.getRoot().toPath().resolve("escaped_households.xml.gz")));
	}

	@Test
	public void relativePathInASubdirectoryCreatesTheSubdirectory() throws IOException {
		Path outputDirectory = outputDirectory();
		Path produced = writeOutputHouseholds(outputDirectory);

		Outcome outcome = SimWrapperHouseholdsInputLinker.ensureHouseholdsInput(outputDirectory,
				"input/scenario_households.xml.gz", produced);

		assertTrue(outcome == Outcome.LINKED || outcome == Outcome.COPIED);
		assertEquals(Files.size(produced), Files.size(outputDirectory.resolve("input/scenario_households.xml.gz")));
	}

	@Test
	public void shutdownListenerLinksTheConfiguredHouseholdsFile() throws IOException {
		Path root = temporaryFolder.getRoot().toPath().resolve("listener_output");
		OutputDirectoryHierarchy hierarchy = new OutputDirectoryHierarchy(root.toString(), null,
				OverwriteFileSetting.deleteDirectoryIfExists, CompressionType.gzip);
		Config config = ConfigUtils.createConfig();
		config.households().setInputFile(CONFIGURED_NAME);
		config.controller().setCompressionType(CompressionType.gzip);
		Path produced = writeOutputHouseholds(root);
		// The listener must look for the file under the name MATSim's DumpDataAtEnd writes.
		assertEquals(produced.toFile().getName(),
				new File(hierarchy.getOutputFilename(Controler.DefaultFiles.households, CompressionType.gzip))
						.getName());

		new SimWrapperHouseholdsInputLinker(config, hierarchy).notifyShutdown(new ShutdownEvent(null, false, 0, null));

		assertTrue(Files.exists(root.resolve(CONFIGURED_NAME)));
	}

	@Test
	public void shutdownListenerDoesNothingAfterAnUnexpectedTermination() throws IOException {
		Path root = temporaryFolder.getRoot().toPath().resolve("listener_output");
		OutputDirectoryHierarchy hierarchy = new OutputDirectoryHierarchy(root.toString(), null,
				OverwriteFileSetting.deleteDirectoryIfExists, CompressionType.gzip);
		Config config = ConfigUtils.createConfig();
		config.households().setInputFile(CONFIGURED_NAME);
		writeOutputHouseholds(root);

		new SimWrapperHouseholdsInputLinker(config, hierarchy).notifyShutdown(new ShutdownEvent(null, true, 0, null));

		assertFalse(Files.exists(root.resolve(CONFIGURED_NAME)));
	}

	/**
	 * Runs a minimal real MATSim controller and records, at the priority of the SimWrapperListener (-1000.0), whether
	 * the households input name is resolvable in the output directory by then.
	 */
	@Test
	public void inARealControllerRunTheLinkExistsWhenTheSimWrapperPriorityListenerRuns() throws IOException {
		Path root = temporaryFolder.getRoot().toPath().resolve("controller_output");
		Config config = ConfigUtils.createConfig();
		config.controller().setOutputDirectory(root.toString());
		config.controller().setLastIteration(0);
		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setCompressionType(CompressionType.gzip);
		config.controller().setCreateGraphs(false);
		config.households().setInputFile(CONFIGURED_NAME);
		ActivityParams activityParams = new ActivityParams("h");
		activityParams.setTypicalDuration(3600.0);
		config.scoring().addActivityParams(activityParams);

		Scenario scenario = ScenarioUtils.createScenario(config);
		Household household = scenario.getHouseholds().getFactory().createHousehold(Id.create("h1", Household.class));
		scenario.getHouseholds().getHouseholds().put(household.getId(), household);

		boolean[] linkResolvableAtSimWrapperPriority = new boolean[1];
		boolean[] householdsOutputExistsAtSimWrapperPriority = new boolean[1];

		Controler controller = new Controler(scenario);
		controller.addOverridingModule(new SimWrapperAnalysisInputModule());
		controller.addControlerListener(new ShutdownListener() {
			@Override
			public double priority() {
				return -1000.0;
			}

			@Override
			public void notifyShutdown(ShutdownEvent event) {
				householdsOutputExistsAtSimWrapperPriority[0] = Files.exists(root.resolve("output_households.xml.gz"));
				linkResolvableAtSimWrapperPriority[0] = Files.exists(root.resolve(CONFIGURED_NAME));
			}
		});
		controller.run();

		assertTrue("MATSim wrote output_households.xml.gz before the SimWrapper priority",
				householdsOutputExistsAtSimWrapperPriority[0]);
		assertTrue("the configured households name is resolvable before the SimWrapper priority",
				linkResolvableAtSimWrapperPriority[0]);
	}

	@Test
	public void listenerRunsAfterMatsimWritesItsOutputsAndBeforeSimWrapper() {
		double priority = new SimWrapperHouseholdsInputLinker(ConfigUtils.createConfig(), null).priority();

		// MATSim's DumpDataAtEnd writes output_households.xml.gz with priority 0.0 and the SimWrapperListener runs
		// the analyses with priority -1000.0; higher priorities are called first.
		assertTrue("priority must be below 0.0 (after DumpDataAtEnd), was " + priority, priority < 0.0);
		assertTrue("priority must be above -1000.0 (before SimWrapperListener), was " + priority,
				priority > -1000.0);
	}
}
