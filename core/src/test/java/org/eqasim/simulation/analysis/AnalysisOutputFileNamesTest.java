package org.eqasim.simulation.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eqasim.core.simulation.analysis.AnalysisOutputListener;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.application.ApplicationUtils;
import org.matsim.core.config.groups.ControllerConfigGroup.CompressionType;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;

/**
 * Guards the file naming of the eqasim analysis output against collisions with MATSim's own lookup of its
 * per-iteration tables.
 *
 * MATSim's SimWrapper TripAnalysis locates the first iteration's trips with
 * {@code ApplicationUtils.matchInput("trips.csv", ITERS/it.0)}. That lookup falls back to a SUFFIX match, so an
 * eqasim file whose name ends in {@code trips.csv(.gz)} can be picked instead of MATSim's {@code 0.trips.csv.gz},
 * which then fails to parse because the eqasim schema differs.
 */
public class AnalysisOutputFileNamesTest {
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	private OutputDirectoryHierarchy createHierarchy() throws IOException {
		String outputPath = new File(temporaryFolder.getRoot(), "output").getAbsolutePath();
		OutputDirectoryHierarchy hierarchy = new OutputDirectoryHierarchy(outputPath, null,
				OverwriteFileSetting.deleteDirectoryIfExists, CompressionType.gzip);
		hierarchy.createIterationDirectory(0);
		return hierarchy;
	}

	private void touch(String path) throws IOException {
		Files.write(new File(path).toPath(), new byte[0]);
	}

	private Path matchTrips(Path iterationDirectory) {
		return ApplicationUtils.matchInput("trips.csv", iterationDirectory);
	}

	@Test
	public void matsimTripsLookupIgnoresEqasimIterationTrips_matsimFileCreatedFirst() throws IOException {
		OutputDirectoryHierarchy hierarchy = createHierarchy();
		String matsimTrips = hierarchy.getIterationFilename(0, "trips.csv", CompressionType.gzip);
		String eqasimTrips = hierarchy.getIterationFilename(0, AnalysisOutputListener.ITERATION_TRIPS_FILE_NAME,
				CompressionType.gzip);

		touch(matsimTrips);
		touch(eqasimTrips);

		assertEquals(new File(matsimTrips).toPath(), matchTrips(new File(matsimTrips).toPath().getParent()));
	}

	@Test
	public void matsimTripsLookupIgnoresEqasimIterationTrips_eqasimFileCreatedFirst() throws IOException {
		OutputDirectoryHierarchy hierarchy = createHierarchy();
		String matsimTrips = hierarchy.getIterationFilename(0, "trips.csv", CompressionType.gzip);
		String eqasimTrips = hierarchy.getIterationFilename(0, AnalysisOutputListener.ITERATION_TRIPS_FILE_NAME,
				CompressionType.gzip);

		touch(eqasimTrips);
		touch(matsimTrips);

		assertEquals(new File(matsimTrips).toPath(), matchTrips(new File(matsimTrips).toPath().getParent()));
	}

	@Test
	public void matsimTripsLookupFindsNothingFromEqasimIterationTripsAlone() throws IOException {
		OutputDirectoryHierarchy hierarchy = createHierarchy();
		String eqasimTrips = hierarchy.getIterationFilename(0, AnalysisOutputListener.ITERATION_TRIPS_FILE_NAME,
				CompressionType.gzip);
		touch(eqasimTrips);

		try {
			Path match = matchTrips(new File(eqasimTrips).toPath().getParent());
			throw new AssertionError("MATSim trips lookup must not match an eqasim file, but returned " + match);
		} catch (IllegalArgumentException expected) {
			// ApplicationUtils.matchInput signals "no match" with an IllegalArgumentException.
		}
	}

	@Test
	public void iterationTripsNameDoesNotSuffixMatchMatsimTripsName() {
		assertFalse(AnalysisOutputListener.ITERATION_TRIPS_FILE_NAME.endsWith("trips.csv"));
	}

	@Test
	public void outputRootTripsNameIsUnchanged() throws IOException {
		assertEquals("eqasim_trips.csv", AnalysisOutputListener.TRIPS_FILE_NAME);

		OutputDirectoryHierarchy hierarchy = createHierarchy();
		String rootFile = hierarchy.getOutputFilename(AnalysisOutputListener.TRIPS_FILE_NAME, CompressionType.gzip);
		assertTrue(rootFile, rootFile.endsWith("eqasim_trips.csv.gz"));
	}
}
