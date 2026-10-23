package org.eqasim.core.scenario.routing;

import java.util.Collections;
import java.util.Set;

import org.matsim.core.controler.AbstractModule;

import com.google.inject.Provider;
import com.google.inject.Provides;

public class PopulationRouterModule extends AbstractModule {
	private final int numberOfThreads;
	private final int batchSize;
	private final boolean replaceExistingRoutes;
	private final Set<String> modes;
	private final DayEndShift dayEndShift;

	public PopulationRouterModule(int numberOfThreads, int batchSize, boolean replaceExistingRoute) {
		this(numberOfThreads, batchSize, replaceExistingRoute, Collections.emptySet());
	}

	public PopulationRouterModule(int numberOfThreads, int batchSize, boolean replaceExistingRoutes,
			Set<String> modes) {
		this(numberOfThreads, batchSize, replaceExistingRoutes, modes, null);
	}

	/** @param dayEndShift routed day-end shift (eqasim-bs ADR-0160), null for none */
	public PopulationRouterModule(int numberOfThreads, int batchSize, boolean replaceExistingRoutes,
			Set<String> modes, DayEndShift dayEndShift) {
		this.numberOfThreads = numberOfThreads;
		this.batchSize = batchSize;
		this.replaceExistingRoutes = replaceExistingRoutes;
		this.modes = modes;
		this.dayEndShift = dayEndShift;
	}

	@Override
	public void install() {
		bind(PlanRouter.class);
	}

	@Provides
	public PopulationRouter providePopulationRouter(Provider<PlanRouter> routerProvider) {
		return new PopulationRouter(numberOfThreads, batchSize, replaceExistingRoutes, modes, routerProvider,
				dayEndShift);
	}
}
