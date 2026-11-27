package org.eqasim.braunschweig.analysis;

import org.matsim.core.controler.AbstractModule;

import com.google.inject.Singleton;

/**
 * Installs {@link LoopTripDistanceStatsListener}, which writes MATSim's distance statistics with the survey diary
 * length of the zero-length loop trips next to MATSim's own files (eqasim-bs#679). Without loop trips in the plans the
 * listener writes MATSim's values, so the module is installed in every Braunschweig run.
 */
public class LoopTripDistanceStatsModule extends AbstractModule {
	@Override
	public void install() {
		bind(LoopTripDistanceStatsListener.class).in(Singleton.class);
		addControllerListenerBinding().to(LoopTripDistanceStatsListener.class);
	}
}
