package org.eqasim.scenario.cutter;

import org.eqasim.core.misc.Constants.TransitCutMode;
import org.eqasim.core.scenario.cutter.RunScenarioCutter;
import org.junit.Assert;
import org.junit.Test;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;

/** The transit cut mode options of the scenario cutter (eqasim-bs#574). */
public class TestRunScenarioCutterTransitCutMode {
	private static CommandLine commandLine(String... args) throws ConfigurationException {
		return new CommandLine.Builder(args) //
				.allowOptions(RunScenarioCutter.TRANSIT_CUT_MODE, RunScenarioCutter.TRANSIT_HUB_STOP_NAMES_PATH) //
				.build();
	}

	@Test
	public void theDefaultIsTheExtentMode() throws ConfigurationException {
		Assert.assertEquals(TransitCutMode.EXTENT, RunScenarioCutter.parseTransitCutMode(commandLine()));
	}

	@Test
	public void theKeptStopsModeAcceptsHubNames() throws ConfigurationException {
		Assert.assertEquals(TransitCutMode.KEPT_STOPS, RunScenarioCutter.parseTransitCutMode(
				commandLine("--transit-cut-mode", "kept-stops", "--transit-hub-stop-names-path", "hubs.txt")));
	}

	@Test(expected = IllegalArgumentException.class)
	public void hubNamesAreRefusedInTheExtentMode() throws ConfigurationException {
		RunScenarioCutter.parseTransitCutMode(commandLine("--transit-hub-stop-names-path", "hubs.txt"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void anUnknownModeFails() throws ConfigurationException {
		RunScenarioCutter.parseTransitCutMode(commandLine("--transit-cut-mode", "polygon"));
	}
}
