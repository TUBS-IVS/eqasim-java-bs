package org.eqasim.braunschweig.fares.zonal;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.controler.AbstractModule;

import com.google.inject.Provides;
import com.google.inject.Singleton;

/**
 * Guice wiring of the compact VRB zone fare model (ADR-0133); installed by BraunschweigModeChoiceModule
 * only when the vrbFare module is enabled. Input paths are resolved relative to the config file.
 *
 * <p>{@link VrbFareConfigGroup} is not bound here: MATSim's bootstrap injector already binds every typed
 * config group of the config, and a second binding in this child module fails the controller start.
 * BraunschweigConfigurator.updateConfig has checked the group (enabled, supported values) before.
 */
public final class VrbFareModule extends AbstractModule {
	@Override
	public void install() {
		bind(FareOutcomeCounter.class).asEagerSingleton();
		bind(VrbZoneFareCostModel.class).in(Singleton.class);
		bind(FareQuoteSource.class).to(VrbZoneFareCostModel.class);
		addControlerListenerBinding().to(FareOutcomeReportListener.class);
	}

	@Provides
	@Singleton
	public VrbFareModel provideFareModel(Config config, VrbFareConfigGroup fare) throws IOException {
		return VrbFareModel.read(resolve(config, fare.getFareModelPath()));
	}

	@Provides
	@Singleton
	public PtLineScopes provideLineScopes(Config config, VrbFareConfigGroup fare) throws IOException {
		return PtLineScopes.read(resolve(config, fare.getLineScopesPath()));
	}

	/** Paths in the module are relative to the config file, like every other MATSim input path. */
	static Path resolve(Config config, String path) {
		URL url = ConfigGroup.getInputFileURL(config.getContext(), path);
		try {
			return Path.of(url.toURI());
		} catch (URISyntaxException error) {
			throw new IllegalArgumentException("cannot resolve vrbFare input path " + path, error);
		}
	}
}
