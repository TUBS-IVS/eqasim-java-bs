package org.eqasim.braunschweig.parking;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.controler.AbstractModule;

import com.google.inject.Provides;
import com.google.inject.Singleton;

/**
 * Guice wiring of the zone-based parking costs (design "parking cost zones", sections 3.6 and 5.2; eqasim-bs issue
 * #436); installed by BraunschweigModeChoiceModule only when the braunschweigParking module is enabled. Provides the
 * tariff model read from {@code braunschweigParking.tariffsPath}, resolved relative to the config file like every
 * MATSim input path (the preparation stage writes the JSON next to the prepared config), and binds the outcome counter
 * and the per-iteration outcome report.
 *
 * <p>{@link ParkingConfigGroup} is not bound here: MATSim's bootstrap injector already binds every typed config group
 * of the config, and a second binding in this module fails the controller start (as for VrbFareModule).
 * BraunschweigConfigurator.updateConfig has checked the group (enabled, tariffsPath set) before.
 */
public final class ParkingModule extends AbstractModule {
	@Override
	public void install() {
		bind(ParkingOutcomeCounter.class).asEagerSingleton();
		addControlerListenerBinding().to(ParkingOutcomeReportListener.class);
	}

	/**
	 * The tariff model of the run, read once.
	 *
	 * @throws IllegalArgumentException when the resolved path is not a file, naming the parameter and the resolved path
	 * @throws IOException when the file cannot be read or is not valid JSON
	 */
	@Provides
	@Singleton
	public ParkingTariffs provideTariffs(Config config, ParkingConfigGroup parking) throws IOException {
		Path path = resolve(config, parking.getTariffsPath());
		if (!Files.isRegularFile(path)) {
			throw new IllegalArgumentException(ParkingConfigGroup.GROUP_NAME + ".tariffsPath " + parking.getTariffsPath()
					+ " resolves to " + path + ", which is not a file; the path is relative to the config file, next to"
					+ " which the preparation stage writes the tariff model");
		}
		return ParkingTariffs.read(path);
	}

	/** A path of the module relative to the config file, like every other MATSim input path; absolute paths stay. */
	static Path resolve(Config config, String path) {
		URL url = ConfigGroup.getInputFileURL(config.getContext(), path);
		try {
			return Path.of(url.toURI());
		} catch (URISyntaxException error) {
			throw new IllegalArgumentException("cannot resolve " + ParkingConfigGroup.GROUP_NAME + ".tariffsPath " + path,
					error);
		}
	}
}
