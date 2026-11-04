package org.eqasim.core.simulation.mode_choice.trip_cache;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripEstimator;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.core.controler.AbstractModule;

import com.google.inject.Provider;
import com.google.inject.Provides;

/**
 * Replaces the TripEstimator binding of the DMC contrib's EstimatorModule: the same named trip estimator
 * (discreteModeChoice.tripEstimator) and the same cached modes (discreteModeChoice.cachedModes), cached by a
 * PersonScopedCachedTripEstimator instead of the contrib's CachedTripEstimator, whose lookups slow down with every trip
 * a replanning thread estimates (see PersonScopedCachedTripEstimator). The estimates are the same.
 *
 * <p>It must be added as an overriding module after the DiscreteModeChoiceModule, as EqasimConfigurator does. Like the
 * contrib's provider it is unscoped: every injection gets its own cache, so a replanning thread owns its cache.
 */
public final class PersonScopedTripCacheModule extends AbstractModule {
	private static final Logger LOG = LogManager.getLogger(PersonScopedTripCacheModule.class);

	/**
	 * The provider runs once per replanning thread and iteration, and MATSim installs a module several times while it
	 * combines the overriding modules; the cache is therefore reported once per JVM, on the first provided estimator.
	 */
	private static final AtomicBoolean REPORTED = new AtomicBoolean(false);

	@Override
	public void install() {
		// Only the provider below
	}

	@Provides
	TripEstimator provideTripEstimator(DiscreteModeChoiceConfigGroup dmcConfig,
			Map<String, Provider<TripEstimator>> providers) {
		Provider<TripEstimator> provider = providers.get(dmcConfig.getTripEstimator());
		if (provider == null) {
			// The message of the contrib's EstimatorModule, which this binding replaces
			throw new IllegalStateException(
					String.format("There is no TripEstimator component called '%s',", dmcConfig.getTripEstimator()));
		}
		if (REPORTED.compareAndSet(false, true)) {
			LOG.info("DMC trip estimates of the modes {} are cached per person (PersonScopedCachedTripEstimator "
					+ "around {}) instead of by the contrib's CachedTripEstimator; the estimates are the same",
					dmcConfig.getCachedModes(), dmcConfig.getTripEstimator());
		}
		return new PersonScopedCachedTripEstimator(provider.get(), dmcConfig.getCachedModes());
	}
}
