package org.eqasim.braunschweig.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

/**
 * The tariff model path of the braunschweigParking module is resolved like every MATSim input path: relative to the
 * config file, not to the working directory (the preparation stage writes the JSON next to the prepared config).
 */
public class ParkingModuleTest {
	private static final String FIXTURE_RESOURCE = "/parking/parking_tariffs_fixture.json";

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static Path fixturePath() throws Exception {
		URL resource = ParkingModuleTest.class.getResource(FIXTURE_RESOURCE);
		assertNotNull("test resource " + FIXTURE_RESOURCE + " is missing", resource);
		return Path.of(resource.toURI());
	}

	/** A config loaded from {@code directory/config.xml}, as RunSimulation loads the prepared config. */
	private static Config configIn(Path directory) throws Exception {
		Config config = ConfigUtils.createConfig();
		config.setContext(directory.resolve("config.xml").toUri().toURL());
		return config;
	}

	private static ParkingConfigGroup enabled(String tariffsPath) {
		ParkingConfigGroup parking = new ParkingConfigGroup();
		parking.setEnabled("true");
		parking.setTariffsPath(tariffsPath);
		return parking;
	}

	@Test
	public void relativeTariffsPathIsReadNextToTheConfigFile() throws Exception {
		Path prepared = folder.newFolder("prepared").toPath();
		Files.copy(fixturePath(), prepared.resolve("parking_tariffs_2026-09-28.json"));
		Config config = configIn(prepared);

		assertEquals(prepared.resolve("parking_tariffs_2026-09-28.json"),
				ParkingModule.resolve(config, "parking_tariffs_2026-09-28.json"));
		ParkingTariffs tariffs = new ParkingModule().provideTariffs(config, enabled("parking_tariffs_2026-09-28.json"));
		assertEquals(8, tariffs.zones().size());
		assertTrue(tariffs.zone("fx_bs_ia").isPresent());
	}

	@Test
	public void absoluteTariffsPathIsUsedAsItIs() throws Exception {
		Config config = configIn(folder.newFolder("elsewhere").toPath());
		Path absolute = fixturePath().toAbsolutePath();
		assertEquals(absolute, ParkingModule.resolve(config, absolute.toString()));
		assertEquals(8, new ParkingModule().provideTariffs(config, enabled(absolute.toString())).zones().size());
	}

	@Test
	public void missingTariffFileFailsNamingTheParameterAndTheResolvedPath() throws Exception {
		Path prepared = folder.newFolder("prepared_without_tariffs").toPath();
		Config config = configIn(prepared);
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> new ParkingModule().provideTariffs(config, enabled("parking_tariffs_missing.json")));
		assertTrue(error.getMessage(), error.getMessage().contains("braunschweigParking.tariffsPath"));
		assertTrue(error.getMessage(), error.getMessage().contains(prepared.resolve("parking_tariffs_missing.json").toString()));
	}
}
