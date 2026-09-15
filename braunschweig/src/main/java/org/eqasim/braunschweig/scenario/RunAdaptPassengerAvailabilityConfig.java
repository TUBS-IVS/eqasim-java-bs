package org.eqasim.braunschweig.scenario;

import org.matsim.core.config.CommandLine.ConfigurationException;

/**
 * Capability entry point for scenarios carrying the optional
 * {@code carPassengerAvailability} person attribute. The separate class lets the
 * Python preparation pipeline fail visibly when an older Braunschweig JAR cannot
 * consume that attribute, while delegating to the unchanged configuration
 * adaptation.
 */
public final class RunAdaptPassengerAvailabilityConfig {
	private RunAdaptPassengerAvailabilityConfig() {
	}

	public static void main(String[] args) throws ConfigurationException {
		RunAdaptConfig.main(args);
	}
}
