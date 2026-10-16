package org.eqasim.braunschweig.simwrapper;

import org.matsim.core.controler.AbstractModule;

/**
 * Installs the listeners that prepare the output directory for the MATSim SimWrapper analyses. Added together with the
 * {@code SimWrapperModule}, i.e. only when SimWrapper dashboards are requested; a run without SimWrapper is not
 * affected.
 */
public class SimWrapperAnalysisInputModule extends AbstractModule {
	@Override
	public void install() {
		addControllerListenerBinding().to(SimWrapperHouseholdsInputLinker.class);
	}
}
