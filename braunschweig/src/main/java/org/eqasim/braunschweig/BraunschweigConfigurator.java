package org.eqasim.braunschweig;

import org.eqasim.core.simulation.EqasimConfigurator;
import org.eqasim.braunschweig.mode_choice.BraunschweigModeChoiceModule;
import org.matsim.core.config.CommandLine;

public class BraunschweigConfigurator extends EqasimConfigurator {
	public BraunschweigConfigurator(CommandLine cmd) {
		super(cmd);

		registerModule(new BraunschweigModeChoiceModule(cmd));
	}
}
