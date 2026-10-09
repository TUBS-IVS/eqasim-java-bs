package org.eqasim.simulation.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.eqasim.core.components.config.EqasimConfigGroup;
import org.eqasim.core.simulation.analysis.AnalysisOutputListener;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.config.groups.ControllerConfigGroup.CompressionType;
import org.matsim.core.config.groups.GlobalConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.controler.events.ShutdownEvent;

/**
 * The copy of the last iteration's eqasim tables to the output root happens at shutdown. Downstream analyses depend
 * on the root files, so a failed copy must fail loudly; only cases that legitimately write no iteration file may be
 * skipped, and those must be explicit.
 */
public class AnalysisOutputListenerShutdownTest {
	private static final int LAST_ITERATION = 0;

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	private OutputDirectoryHierarchy hierarchy;

	private AnalysisOutputListener createListener(int analysisInterval, int travelTimeRecordingInterval)
			throws IOException {
		String outputPath = new File(temporaryFolder.getRoot(), "output").getAbsolutePath();
		hierarchy = new OutputDirectoryHierarchy(outputPath, null, OverwriteFileSetting.deleteDirectoryIfExists,
				CompressionType.gzip);
		hierarchy.createIterationDirectory(LAST_ITERATION);

		EqasimConfigGroup eqasimConfig = new EqasimConfigGroup();
		eqasimConfig.setAnalysisInterval(analysisInterval);
		eqasimConfig.setTravelTimeRecordingInterval(travelTimeRecordingInterval);

		// The shutdown copy does not use the analysis listeners, so they are not needed here.
		ControllerConfigGroup controllerConfig = new ControllerConfigGroup();
		controllerConfig.setCompressionType(CompressionType.gzip);

		return new AnalysisOutputListener(eqasimConfig, hierarchy, null, null, null, null, null, controllerConfig,
				new GlobalConfigGroup());
	}

	private File iterationFile(String name) {
		return new File(hierarchy.getIterationFilename(LAST_ITERATION, name, CompressionType.gzip));
	}

	private File rootFile(String name) {
		return new File(hierarchy.getOutputFilename(name, CompressionType.gzip));
	}

	private void writeIterationFile(String name, String content) throws IOException {
		Files.write(iterationFile(name).toPath(), content.getBytes(StandardCharsets.UTF_8));
	}

	private void writeAllIterationFiles() throws IOException {
		writeIterationFile(AnalysisOutputListener.ITERATION_TRIPS_FILE_NAME, "trips");
		writeIterationFile(AnalysisOutputListener.ITERATION_LEGS_FILE_NAME, "legs");
		writeIterationFile(AnalysisOutputListener.PT_FILE_NAME, "pt");
		writeIterationFile(AnalysisOutputListener.ITERATION_ACTIVITIES_FILE_NAME, "activities");
		writeIterationFile(AnalysisOutputListener.TRAVEL_TIMES_FILE_NAME, "travel_times");
	}

	private ShutdownEvent regularShutdown() {
		return new ShutdownEvent(null, false, LAST_ITERATION, null);
	}

	@Test
	public void allIterationFilesAreCopiedToOutputRootUnderUnchangedNames() throws IOException {
		AnalysisOutputListener listener = createListener(1, 1);
		writeAllIterationFiles();

		listener.notifyShutdown(regularShutdown());

		assertEquals("trips", Files.readString(rootFile("eqasim_trips.csv").toPath()));
		assertEquals("legs", Files.readString(rootFile("eqasim_legs.csv").toPath()));
		assertEquals("pt", Files.readString(rootFile("eqasim_pt.csv").toPath()));
		assertEquals("activities", Files.readString(rootFile("eqasim_activities.csv").toPath()));
		assertEquals("travel_times", Files.readString(rootFile("eqasim_travel_times.bin").toPath()));
	}

	@Test
	public void missingIterationTripsFileFailsLoudlyAndNamesTheFilePair() throws IOException {
		AnalysisOutputListener listener = createListener(1, 0);
		// Everything except the trips table exists.
		writeIterationFile(AnalysisOutputListener.ITERATION_LEGS_FILE_NAME, "legs");
		writeIterationFile(AnalysisOutputListener.PT_FILE_NAME, "pt");
		writeIterationFile(AnalysisOutputListener.ITERATION_ACTIVITIES_FILE_NAME, "activities");

		try {
			listener.notifyShutdown(regularShutdown());
			fail("A missing iteration trips file must not be swallowed");
		} catch (UncheckedIOException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(
					iterationFile(AnalysisOutputListener.ITERATION_TRIPS_FILE_NAME).getName()));
			assertTrue(e.getMessage(), e.getMessage().contains(rootFile("eqasim_trips.csv").getName()));
		}
		assertFalse(rootFile("eqasim_trips.csv").exists());
	}

	@Test
	public void missingIterationTravelTimesFileFailsLoudlyWhenRecordingIsEnabled() throws IOException {
		AnalysisOutputListener listener = createListener(1, 1);
		writeIterationFile(AnalysisOutputListener.ITERATION_TRIPS_FILE_NAME, "trips");
		writeIterationFile(AnalysisOutputListener.ITERATION_LEGS_FILE_NAME, "legs");
		writeIterationFile(AnalysisOutputListener.PT_FILE_NAME, "pt");
		writeIterationFile(AnalysisOutputListener.ITERATION_ACTIVITIES_FILE_NAME, "activities");

		try {
			listener.notifyShutdown(regularShutdown());
			fail("A missing iteration travel time file must not be swallowed");
		} catch (UncheckedIOException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(AnalysisOutputListener.TRAVEL_TIMES_FILE_NAME));
		}
	}

	@Test
	public void disabledAnalysisAndRecordingSkipTheCopyWithoutError() throws IOException {
		AnalysisOutputListener listener = createListener(0, 0);

		listener.notifyShutdown(regularShutdown());

		assertFalse(rootFile("eqasim_trips.csv").exists());
		assertFalse(rootFile("eqasim_travel_times.bin").exists());
	}

	@Test
	public void unexpectedShutdownDoesNotMaskTheOriginalFailure() throws IOException {
		AnalysisOutputListener listener = createListener(1, 1);

		// The run aborted before the last iteration wrote its tables; the original exception must stay visible.
		listener.notifyShutdown(new ShutdownEvent(null, true, LAST_ITERATION, new RuntimeException("original")));

		assertFalse(rootFile("eqasim_trips.csv").exists());
	}
}
