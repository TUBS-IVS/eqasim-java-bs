package org.eqasim.braunschweig.scenario;

import org.matsim.application.prepare.longDistanceFreightGER.tripExtraction.ExtractRelevantFreightTrips;

import picocli.CommandLine;

/**
 * Thin entry point around the matsim application contrib command
 * {@link ExtractRelevantFreightTrips} (Lu et al. 2022, german-wide-freight),
 * so the pipeline can launch it via {@code java -cp braunschweig.jar} like
 * every other eqasim step. All CLI options are the contrib command's own.
 */
public class RunExtractFreightTrips {
	public static void main(String[] args) {
		System.exit(new CommandLine(new ExtractRelevantFreightTrips()).execute(args));
	}
}
