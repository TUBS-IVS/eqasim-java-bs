package org.eqasim.simulation.analysis;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.eqasim.core.components.config.EqasimConfigGroup;
import org.eqasim.core.simulation.modes.drt.analysis.DrtAnalysisListener;
import org.eqasim.core.simulation.modes.drt.analysis.utils.VehicleRegistry;
import org.eqasim.core.simulation.modes.feeder_drt.analysis.FeederDrtAnalysisListener;
import org.eqasim.core.simulation.modes.feeder_drt.config.MultiModeFeederDrtConfigGroup;
import org.eqasim.core.simulation.modes.transit_with_abstract_access.analysis.AbstractAccessAnalysisOutputListener;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.core.config.groups.ControllerConfigGroup.CompressionType;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.network.NetworkUtils;

/**
 * The mode-specific analysis listeners (DRT, feeder DRT, abstract access) copy the last iteration's table to the
 * output root at shutdown. A file left over from a previous run in the same output directory must be replaced by the
 * new content, neither aborting the run nor leaving the stale file in place.
 */
public class ModeAnalysisShutdownCopyTest {
	private static final int LAST_ITERATION = 0;

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	private OutputDirectoryHierarchy hierarchy;
	private EqasimConfigGroup eqasimConfig;

	@Before
	public void setUp() throws IOException {
		String outputPath = new File(temporaryFolder.getRoot(), "output").getAbsolutePath();
		hierarchy = new OutputDirectoryHierarchy(outputPath, null, OverwriteFileSetting.deleteDirectoryIfExists,
				CompressionType.none);
		hierarchy.createIterationDirectory(LAST_ITERATION);

		eqasimConfig = new EqasimConfigGroup();
		eqasimConfig.setAnalysisInterval(1);
	}

	private ShutdownEvent regularShutdown() {
		return new ShutdownEvent(null, false, LAST_ITERATION, null);
	}

	private void write(String path, String content) throws IOException {
		Files.write(new File(path).toPath(), content.getBytes(StandardCharsets.UTF_8));
	}

	private String read(String path) throws IOException {
		return Files.readString(new File(path).toPath());
	}

	/** Writes the iteration file and a stale output-root file of a previous run. */
	private void prepare(String iterationName, String outputName, String iterationContent) throws IOException {
		write(hierarchy.getIterationFilename(LAST_ITERATION, iterationName), iterationContent);
		write(hierarchy.getOutputFilename(outputName), "stale");
	}

	@Test
	public void drtShutdownReplacesExistingOutputRootFiles() throws IOException {
		DrtAnalysisListener listener = new DrtAnalysisListener(eqasimConfig, new MultiModeDrtConfigGroup(), hierarchy,
				NetworkUtils.createNetwork(), new VehicleRegistry());

		prepare("eqasim_drt_passenger_rides.csv", "eqasim_drt_passenger_rides.csv", "rides");
		prepare("eqasim_drt_vehicle_movements.csv", "eqasim_drt_vehicle_movements.csv", "movements");
		prepare(DrtAnalysisListener.ITERATION_VEHICLE_ACTIVITIES_FILE_NAME,
				DrtAnalysisListener.VEHICLE_ACTIVITIES_FILE_NAME, "activities");

		listener.notifyShutdown(regularShutdown());

		assertEquals("rides", read(hierarchy.getOutputFilename("eqasim_drt_passenger_rides.csv")));
		assertEquals("movements", read(hierarchy.getOutputFilename("eqasim_drt_vehicle_movements.csv")));
		assertEquals("activities", read(hierarchy.getOutputFilename(DrtAnalysisListener.VEHICLE_ACTIVITIES_FILE_NAME)));
	}

	@Test
	public void feederDrtShutdownReplacesExistingOutputRootFile() throws IOException {
		FeederDrtAnalysisListener listener = new FeederDrtAnalysisListener(eqasimConfig,
				new MultiModeFeederDrtConfigGroup(), hierarchy, NetworkUtils.createNetwork());

		prepare(FeederDrtAnalysisListener.ITERATION_FEEDER_TRIPS_FILE_NAME,
				FeederDrtAnalysisListener.FEEDER_TRIPS_FILE_NAME, "feeder");

		listener.notifyShutdown(regularShutdown());

		assertEquals("feeder", read(hierarchy.getOutputFilename(FeederDrtAnalysisListener.FEEDER_TRIPS_FILE_NAME)));
	}

	@Test
	public void abstractAccessShutdownReplacesExistingOutputRootFile() throws IOException {
		AbstractAccessAnalysisOutputListener listener = new AbstractAccessAnalysisOutputListener(eqasimConfig,
				hierarchy, null);

		prepare(AbstractAccessAnalysisOutputListener.ITERATION_ABSTRACT_ACCESS_LEGS_FILE_NAME,
				AbstractAccessAnalysisOutputListener.ABSTRACT_ACCESS_LEGS_FILE_NAME, "access");

		listener.notifyShutdown(regularShutdown());

		assertEquals("access",
				read(hierarchy.getOutputFilename(AbstractAccessAnalysisOutputListener.ABSTRACT_ACCESS_LEGS_FILE_NAME)));
	}
}
