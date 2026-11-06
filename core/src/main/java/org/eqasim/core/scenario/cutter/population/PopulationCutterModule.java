package org.eqasim.core.scenario.cutter.population;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;

import org.eqasim.core.components.transit.departure.DepartureFinder;
import org.eqasim.core.misc.Constants.TransitCutMode;
import org.eqasim.core.scenario.cutter.extent.ScenarioExtent;
import org.eqasim.core.scenario.cutter.population.trips.ModeAwareTripProcessor;
import org.eqasim.core.scenario.cutter.population.trips.NetworkTripCrossingStatistics;
import org.eqasim.core.scenario.cutter.population.trips.NetworkTripProcessor;
import org.eqasim.core.scenario.cutter.population.trips.TeleportationTripProcessor;
import org.eqasim.core.scenario.cutter.population.trips.TransitTripProcessor;
import org.eqasim.core.scenario.cutter.population.trips.TripProcessor;
import org.eqasim.core.scenario.cutter.population.trips.crossing.network.DefaultNetworkRouteCrossingPointFinder;
import org.eqasim.core.scenario.cutter.population.trips.crossing.network.DefaultNetworkTripCrossingPointFinder;
import org.eqasim.core.scenario.cutter.population.trips.crossing.network.NetworkRouteCrossingPointFinder;
import org.eqasim.core.scenario.cutter.population.trips.crossing.network.NetworkTripCrossingPointFinder;
import org.eqasim.core.scenario.cutter.population.trips.crossing.network.timing.LinkTimingRegistry;
import org.eqasim.core.scenario.cutter.population.trips.crossing.network.timing.LinkTimingRegistryHandler;
import org.eqasim.core.scenario.cutter.population.trips.crossing.teleportation.DefaultTeleportationCrossingPointFinder;
import org.eqasim.core.scenario.cutter.population.trips.crossing.teleportation.TeleportationCrossingPointFinder;
import org.eqasim.core.scenario.cutter.population.trips.crossing.transit.DefaultTransitRouteCrossingPointFinder;
import org.eqasim.core.scenario.cutter.population.trips.crossing.transit.DefaultTransitTripCrossingPointFinder;
import org.eqasim.core.scenario.cutter.population.trips.crossing.transit.KeptStopsTransitTripCrossingPointFinder;
import org.eqasim.core.scenario.cutter.population.trips.crossing.transit.TransitRouteCrossingPointFinder;
import org.eqasim.core.scenario.cutter.population.trips.crossing.transit.TransitTripCrossingPointFinder;
import org.eqasim.core.scenario.cutter.transit.TransitScheduleCutter.KeptRange;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup.TeleportedModeParams;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.pt.config.TransitConfigGroup;
import org.matsim.pt.config.TransitRouterConfigGroup;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.Singleton;

public class PopulationCutterModule extends AbstractModule {
	private final int numberOfThreads;
	private final int batchSize;
	private final ScenarioExtent extent;
	private final Optional<String> eventsPath;
	private final TransitCutMode transitCutMode;
	private final Map<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> keptRanges;

	public PopulationCutterModule(ScenarioExtent extent, int numberOfThreads, int batchSize,
			Optional<String> eventsPath) {
		this(extent, numberOfThreads, batchSize, eventsPath, TransitCutMode.EXTENT, Map.of());
	}

	/**
	 * @param transitCutMode how pt trips are cut (eqasim-bs#574): {@code EXTENT} binds the polygon finders, the
	 *                       default merge and no collapse (upstream behaviour); {@code KEPT_STOPS} binds the
	 *                       kept-stops finder, the attribute-keeping merge, the collapse of excursions and the copy of
	 *                       activity attributes
	 * @param keptRanges     the kept index range per kept route of the uncut schedule (dry pass of the schedule cut);
	 *                       only for {@code KEPT_STOPS}, empty for {@code EXTENT}
	 */
	public PopulationCutterModule(ScenarioExtent extent, int numberOfThreads, int batchSize,
			Optional<String> eventsPath, TransitCutMode transitCutMode,
			Map<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> keptRanges) {
		if (transitCutMode == TransitCutMode.EXTENT && !keptRanges.isEmpty()) {
			throw new IllegalArgumentException("Kept stop ranges are only used by the kept-stops transit cut mode");
		}

		this.numberOfThreads = numberOfThreads;
		this.batchSize = batchSize;
		this.extent = extent;
		this.eventsPath = eventsPath;
		this.transitCutMode = transitCutMode;
		this.keptRanges = keptRanges;
	}

	@Override
	public void install() {
		bind(TripProcessor.class).to(ModeAwareTripProcessor.class);

		bind(TeleportationCrossingPointFinder.class).to(DefaultTeleportationCrossingPointFinder.class);
		bind(NetworkRouteCrossingPointFinder.class).to(DefaultNetworkRouteCrossingPointFinder.class);
		bind(TransitRouteCrossingPointFinder.class).to(DefaultTransitRouteCrossingPointFinder.class);
		bind(NetworkTripCrossingPointFinder.class).to(DefaultNetworkTripCrossingPointFinder.class);

		if (transitCutMode == TransitCutMode.KEPT_STOPS) {
			bind(TransitTripCrossingPointFinder.class).toProvider(new KeptStopsFinderProvider(keptRanges));
			bind(MergeOutsideActivities.class).to(AttributeKeepingMergeOutsideActivities.class);
		} else {
			bind(TransitTripCrossingPointFinder.class).to(DefaultTransitTripCrossingPointFinder.class);
			bind(MergeOutsideActivities.class).to(DefaultMergeOutsideActivities.class);
		}

		bind(TeleportationTripProcessor.class);

		bind(ScenarioExtent.class).toInstance(extent);
	}

	@Provides
	PlanCutter providePlanCutter(TripProcessor tripProcessor, ScenarioExtent extent,
			MergeOutsideActivities mergeOutsideActivities) {
		return new PlanCutter(tripProcessor, extent, mergeOutsideActivities,
				transitCutMode == TransitCutMode.KEPT_STOPS);
	}

	/** Builds the kept-stops finder; Guice injects the fields of the provider instance. */
	static private class KeptStopsFinderProvider implements Provider<TransitTripCrossingPointFinder> {
		private final Map<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> keptRanges;

		@Inject
		private TransitSchedule schedule;

		@Inject
		private DepartureFinder departureFinder;

		@Inject
		private ScenarioExtent extent;

		KeptStopsFinderProvider(Map<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> keptRanges) {
			this.keptRanges = keptRanges;
		}

		@Override
		public TransitTripCrossingPointFinder get() {
			// The schedule is not cut yet: the trips' routes and the ranges refer to the uncut schedule
			return new KeptStopsTransitTripCrossingPointFinder(schedule, departureFinder, extent, keptRanges);
		}
	}

	@Provides
	PopulationCutter providePopulationCutter(Provider<PlanCutter> planCutterProvider, Population population) {
		return new PopulationCutter(planCutterProvider, population.getFactory(), numberOfThreads, batchSize);
	}

	private Collection<String> getTeleportedModes(RoutingConfigGroup routingConfig) {
		Collection<String> teleportedModes = new HashSet<>();

		for (Map.Entry<String, TeleportedModeParams> entry : routingConfig.getTeleportedModeParams().entrySet()) {
			String mode = entry.getKey();
			TeleportedModeParams params = entry.getValue();

			if (params.getTeleportedModeFreespeedFactor() != null) {
				throw new IllegalStateException("Cutter does not support teleportedModeFreespeedFactor yet!");
			}

			if (params.getTeleportedModeSpeed() != null) {
				teleportedModes.add(mode);
			}
		}

		return teleportedModes;
	}

	private boolean checkDisjoint(Collection<String> a, Collection<String> b) {
		for (String element : a) {
			if (b.contains(element)) {
				return false;
			}
		}

		for (String element : b) {
			if (a.contains(element)) {
				return false;
			}
		}

		return true;
	}

	@Provides
	public ModeAwareTripProcessor provideModeAwareTripProcessor(RoutingConfigGroup routingConfig,
			TransitConfigGroup transitConfig, ScenarioExtent extent,
			TeleportationTripProcessor teleportationTripProcessor, NetworkTripProcessor networkTripProcessor,
			TransitTripProcessor transitTripProcessor) {
		ModeAwareTripProcessor tripProcessor = new ModeAwareTripProcessor();

		Collection<String> networkModes = new HashSet<>(routingConfig.getNetworkModes());
		Collection<String> teleportedModes = getTeleportedModes(routingConfig);

		if (!checkDisjoint(networkModes, teleportedModes)) {
			throw new IllegalStateException("Network modes and teleported modes are not disjoint");
		}

		for (String networkMode : networkModes) {
			tripProcessor.setProcessor(networkMode, networkTripProcessor);
		}

		for (String teleportedMode : teleportedModes) {
			tripProcessor.setProcessor(teleportedMode, teleportationTripProcessor);
		}

		if (transitConfig.isUseTransit()) {
			// TODO: This may not only be "pt"!
			tripProcessor.setProcessor(TransportMode.pt, transitTripProcessor);
		}

		return tripProcessor;
	}

	/**
	 * One processor for all threads, so that its collapse counts cover the whole population (it is stateless apart
	 * from these thread-safe counters).
	 */
	@Provides
	@Singleton
	public TransitTripProcessor provideTransitTripProcessor(TransitTripCrossingPointFinder transitPointFinder,
			ScenarioExtent extent, TransitRouterConfigGroup routerConfig) {
		return new TransitTripProcessor(transitPointFinder, extent, routerConfig.getAdditionalTransferTime(),
				transitCutMode == TransitCutMode.KEPT_STOPS);
	}

	@Provides
	public NetworkTripProcessor provideNetworkTripProcessor(NetworkTripCrossingPointFinder networkPointFinder,
			ScenarioExtent extent, TransitRouterConfigGroup routerConfig, NetworkTripCrossingStatistics statistics) {
		return new NetworkTripProcessor(networkPointFinder, extent, statistics);
	}

	@Provides
	@Singleton
	public NetworkTripCrossingStatistics provideNetworkTripCrossingStatistics() {
		return new NetworkTripCrossingStatistics();
	}

	@Provides
	@Singleton
	public LinkTimingRegistry provideLinkTimingRegistry(Network network) {
		LinkTimingRegistry registry = new LinkTimingRegistry();

		if (eventsPath.isPresent()) {
			EventsManager eventsManager = EventsUtils.createEventsManager();
			eventsManager.addHandler(new LinkTimingRegistryHandler(extent, network, registry));

			eventsManager.initProcessing();
			new MatsimEventsReader(eventsManager).readFile(eventsPath.get());
			eventsManager.finishProcessing();
		}

		return registry;
	}
}
