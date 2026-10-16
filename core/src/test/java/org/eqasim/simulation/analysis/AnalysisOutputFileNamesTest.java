package org.eqasim.simulation.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eqasim.core.simulation.analysis.AnalysisOutputListener;
import org.eqasim.core.simulation.modes.drt.analysis.DrtAnalysisListener;
import org.eqasim.core.simulation.modes.feeder_drt.analysis.FeederDrtAnalysisListener;
import org.eqasim.core.simulation.modes.transit_with_abstract_access.analysis.AbstractAccessAnalysisOutputListener;
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
 * MATSim's SimWrapper analyses locate input tables with {@code ApplicationUtils.matchInput("trips.csv", dir)}
 * (and likewise "legs.csv", "activities.csv"). That lookup falls back to a SUFFIX match, so an eqasim file in
 * the same directory whose name ends in {@code trips.csv(.gz)} can be picked instead of MATSim's
 * {@code 0.trips.csv.gz}, which then fails to parse because the eqasim schema differs.
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

	/**
	 * Creates MATSim's and eqasim's iteration-0 file in the requested order and asserts that MATSim's lookup of
	 * lookupName returns MATSim's file.
	 */
	private void assertMatsimFileWins(String lookupName, String matsimName, String eqasimName,
			CompressionType compression, boolean matsimFirst) throws IOException {
		OutputDirectoryHierarchy hierarchy = createHierarchy();
		String matsimFile = hierarchy.getIterationFilename(0, matsimName, compression);
		String eqasimFile = hierarchy.getIterationFilename(0, eqasimName, compression);

		if (matsimFirst) {
			touch(matsimFile);
			touch(eqasimFile);
		} else {
			touch(eqasimFile);
			touch(matsimFile);
		}

		Path directory = new File(matsimFile).toPath().getParent();
		assertEquals(new File(matsimFile).toPath(), ApplicationUtils.matchInput(lookupName, directory));
	}

	/** An eqasim file alone must not satisfy MATSim's lookup (no suffix match of the eqasim name). */
	private void assertEqasimFileNotMatched(String lookupName, String eqasimName, CompressionType compression)
			throws IOException {
		OutputDirectoryHierarchy hierarchy = createHierarchy();
		String eqasimFile = hierarchy.getIterationFilename(0, eqasimName, compression);
		touch(eqasimFile);

		try {
			Path match = ApplicationUtils.matchInput(lookupName, new File(eqasimFile).toPath().getParent());
			throw new AssertionError("MATSim lookup of " + lookupName + " must not match an eqasim file, but returned "
					+ match);
		} catch (IllegalArgumentException expected) {
			// ApplicationUtils.matchInput signals "no match" with an IllegalArgumentException.
		}
	}

	@Test
	public void matsimTripsLookupIgnoresEqasimIterationTrips_matsimFileCreatedFirst() throws IOException {
		assertMatsimFileWins("trips.csv", "trips.csv", AnalysisOutputListener.ITERATION_TRIPS_FILE_NAME,
				CompressionType.gzip, true);
	}

	@Test
	public void matsimTripsLookupIgnoresEqasimIterationTrips_eqasimFileCreatedFirst() throws IOException {
		assertMatsimFileWins("trips.csv", "trips.csv", AnalysisOutputListener.ITERATION_TRIPS_FILE_NAME,
				CompressionType.gzip, false);
	}

	@Test
	public void matsimTripsLookupFindsNothingFromEqasimIterationTripsAlone() throws IOException {
		assertEqasimFileNotMatched("trips.csv", AnalysisOutputListener.ITERATION_TRIPS_FILE_NAME, CompressionType.gzip);
	}

	@Test
	public void matsimLegsLookupIgnoresEqasimIterationLegs_matsimFileCreatedFirst() throws IOException {
		assertMatsimFileWins("legs.csv", "legs.csv", AnalysisOutputListener.ITERATION_LEGS_FILE_NAME,
				CompressionType.gzip, true);
	}

	@Test
	public void matsimLegsLookupIgnoresEqasimIterationLegs_eqasimFileCreatedFirst() throws IOException {
		assertMatsimFileWins("legs.csv", "legs.csv", AnalysisOutputListener.ITERATION_LEGS_FILE_NAME,
				CompressionType.gzip, false);
	}

	@Test
	public void matsimLegsLookupFindsNothingFromEqasimIterationLegsAlone() throws IOException {
		assertEqasimFileNotMatched("legs.csv", AnalysisOutputListener.ITERATION_LEGS_FILE_NAME, CompressionType.gzip);
	}

	@Test
	public void matsimActivitiesLookupIgnoresEqasimIterationActivities_matsimFileCreatedFirst() throws IOException {
		assertMatsimFileWins("activities.csv", "activities.csv", AnalysisOutputListener.ITERATION_ACTIVITIES_FILE_NAME,
				CompressionType.gzip, true);
	}

	@Test
	public void matsimActivitiesLookupIgnoresEqasimIterationActivities_eqasimFileCreatedFirst() throws IOException {
		assertMatsimFileWins("activities.csv", "activities.csv", AnalysisOutputListener.ITERATION_ACTIVITIES_FILE_NAME,
				CompressionType.gzip, false);
	}

	@Test
	public void matsimActivitiesLookupFindsNothingFromEqasimIterationActivitiesAlone() throws IOException {
		assertEqasimFileNotMatched("activities.csv", AnalysisOutputListener.ITERATION_ACTIVITIES_FILE_NAME,
				CompressionType.gzip);
	}

	// The feeder DRT trips file is written without compression (see FeederDrtAnalysisListener).

	@Test
	public void matsimTripsLookupIgnoresFeederDrtIterationTrips_matsimFileCreatedFirst() throws IOException {
		assertMatsimFileWins("trips.csv", "trips.csv", FeederDrtAnalysisListener.ITERATION_FEEDER_TRIPS_FILE_NAME,
				CompressionType.none, true);
	}

	@Test
	public void matsimTripsLookupIgnoresFeederDrtIterationTrips_feederFileCreatedFirst() throws IOException {
		assertMatsimFileWins("trips.csv", "trips.csv", FeederDrtAnalysisListener.ITERATION_FEEDER_TRIPS_FILE_NAME,
				CompressionType.none, false);
	}

	@Test
	public void matsimTripsLookupFindsNothingFromFeederDrtIterationTripsAlone() throws IOException {
		assertEqasimFileNotMatched("trips.csv", FeederDrtAnalysisListener.ITERATION_FEEDER_TRIPS_FILE_NAME,
				CompressionType.none);
	}

	// The DRT vehicle activities and abstract access legs files are written without compression.

	@Test
	public void matsimActivitiesLookupIgnoresDrtVehicleActivities_matsimFileCreatedFirst() throws IOException {
		assertMatsimFileWins("activities.csv", "activities.csv",
				DrtAnalysisListener.ITERATION_VEHICLE_ACTIVITIES_FILE_NAME, CompressionType.none, true);
	}

	@Test
	public void matsimActivitiesLookupIgnoresDrtVehicleActivities_drtFileCreatedFirst() throws IOException {
		assertMatsimFileWins("activities.csv", "activities.csv",
				DrtAnalysisListener.ITERATION_VEHICLE_ACTIVITIES_FILE_NAME, CompressionType.none, false);
	}

	@Test
	public void matsimActivitiesLookupFindsNothingFromDrtVehicleActivitiesAlone() throws IOException {
		assertEqasimFileNotMatched("activities.csv", DrtAnalysisListener.ITERATION_VEHICLE_ACTIVITIES_FILE_NAME,
				CompressionType.none);
	}

	@Test
	public void matsimLegsLookupIgnoresAbstractAccessLegs_matsimFileCreatedFirst() throws IOException {
		assertMatsimFileWins("legs.csv", "legs.csv",
				AbstractAccessAnalysisOutputListener.ITERATION_ABSTRACT_ACCESS_LEGS_FILE_NAME, CompressionType.none,
				true);
	}

	@Test
	public void matsimLegsLookupIgnoresAbstractAccessLegs_abstractAccessFileCreatedFirst() throws IOException {
		assertMatsimFileWins("legs.csv", "legs.csv",
				AbstractAccessAnalysisOutputListener.ITERATION_ABSTRACT_ACCESS_LEGS_FILE_NAME, CompressionType.none,
				false);
	}

	@Test
	public void matsimLegsLookupFindsNothingFromAbstractAccessLegsAlone() throws IOException {
		assertEqasimFileNotMatched("legs.csv",
				AbstractAccessAnalysisOutputListener.ITERATION_ABSTRACT_ACCESS_LEGS_FILE_NAME, CompressionType.none);
	}

	@Test
	public void iterationNamesDoNotEndWithMatsimTableNames() {
		for (String name : new String[] { AnalysisOutputListener.ITERATION_TRIPS_FILE_NAME,
				AnalysisOutputListener.ITERATION_LEGS_FILE_NAME, AnalysisOutputListener.ITERATION_ACTIVITIES_FILE_NAME,
				FeederDrtAnalysisListener.ITERATION_FEEDER_TRIPS_FILE_NAME,
				DrtAnalysisListener.ITERATION_VEHICLE_ACTIVITIES_FILE_NAME,
				AbstractAccessAnalysisOutputListener.ITERATION_ABSTRACT_ACCESS_LEGS_FILE_NAME }) {
			assertFalse(name, name.endsWith("trips.csv"));
			assertFalse(name, name.endsWith("legs.csv"));
			assertFalse(name, name.endsWith("activities.csv"));
		}
	}

	@Test
	public void outputRootNamesAreUnchanged() throws IOException {
		assertEquals("eqasim_trips.csv", AnalysisOutputListener.TRIPS_FILE_NAME);
		assertEquals("eqasim_legs.csv", AnalysisOutputListener.LEGS_FILE_NAME);
		assertEquals("eqasim_activities.csv", AnalysisOutputListener.ACTIVITIES_FILE_NAME);
		assertEquals("eqasim_feeder_drt_trips.csv", FeederDrtAnalysisListener.FEEDER_TRIPS_FILE_NAME);
		assertEquals("eqasim_drt_vehicle_activities.csv", DrtAnalysisListener.VEHICLE_ACTIVITIES_FILE_NAME);
		assertEquals("eqasim_abstract_access_legs.csv",
				AbstractAccessAnalysisOutputListener.ABSTRACT_ACCESS_LEGS_FILE_NAME);

		OutputDirectoryHierarchy hierarchy = createHierarchy();
		String rootFile = hierarchy.getOutputFilename(AnalysisOutputListener.TRIPS_FILE_NAME, CompressionType.gzip);
		assertTrue(rootFile, rootFile.endsWith("eqasim_trips.csv.gz"));
	}
}
