package org.eqasim.braunschweig.scenario;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.braunschweig.scenario.LargeFacilityAccessAssignment.Decision;
import org.eqasim.braunschweig.scenario.LargeFacilityAccessAssignment.Outcome;
import org.eqasim.braunschweig.scenario.LargeFacilityAccessAssignment.Result;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.FacilitiesWriter;
import org.matsim.facilities.MatsimFacilitiesReader;

/**
 * Gives large facilities on low-capacity links a high-capacity access link (issue #465, ADR-0165).
 *
 * <p>
 * Runs between eqasim's {@code RunPreparation} (which attaches every facility to its nearest link and copies
 * that link to the activities) and the population routing. Reads the prepared network, facilities and
 * population, applies {@link LargeFacilityAccessAssignment} with a {@link LargeFacilityAccessLinkSelector},
 * and writes the facilities and population (the output paths may equal the input paths). Logs the rate of
 * adequate, moved and fallback facilities, writes one summary row per large facility, and fails when the
 * fallback share among the facilities that needed a new link exceeds {@code maximum-fallback-share}: a high
 * share means the rule is broken (wrong CRS, a too high minimum capacity, a too small distance).
 * Deterministic (no random numbers).
 * </p>
 *
 * <p>
 * Options (all required, the Python stage {@code matsim.simulation.prepare} passes them):
 * {@code network-path}, {@code input-facilities-path}, {@code input-population-path},
 * {@code output-facilities-path}, {@code output-population-path}, {@code summary-path} (CSV),
 * {@code sample-size} in (0, 1], {@code minimum-activities} (facility size at 100 %),
 * {@code minimum-capacity-veh-per-h}, {@code maximum-distance-m} (metric CRS), {@code maximum-fallback-share}
 * in [0, 1], {@code excluded-facility-prefixes} (comma separated, may be empty).
 * </p>
 */
public final class RunAdaptLargeFacilityAccess {
	private static final Logger logger = LogManager.getLogger(RunAdaptLargeFacilityAccess.class);

	static final String SUMMARY_HEADER = "facility_id;activities_in_sample;activities_100pct;old_link_id;"
			+ "old_link_capacity_veh_per_h;outcome;new_link_id;new_link_capacity_veh_per_h;distance_meters";

	private RunAdaptLargeFacilityAccess() {
	}

	public static void main(String[] args) throws ConfigurationException {
		CommandLine cmd = new CommandLine.Builder(args)
				.requireOptions("network-path", "input-facilities-path", "input-population-path",
						"output-facilities-path", "output-population-path", "summary-path", "sample-size",
						"minimum-activities", "minimum-capacity-veh-per-h", "maximum-distance-m",
						"maximum-fallback-share", "excluded-facility-prefixes")
				.build();
		double sampleSize = Double.parseDouble(cmd.getOptionStrict("sample-size"));
		double minimumActivities = Double.parseDouble(cmd.getOptionStrict("minimum-activities"));
		double minimumCapacityVehiclesPerHour = Double.parseDouble(cmd.getOptionStrict("minimum-capacity-veh-per-h"));
		double maximumDistanceMeters = Double.parseDouble(cmd.getOptionStrict("maximum-distance-m"));
		double maximumFallbackShare = Double.parseDouble(cmd.getOptionStrict("maximum-fallback-share"));
		if (!(maximumFallbackShare >= 0.0 && maximumFallbackShare <= 1.0)) {
			throw new IllegalArgumentException("maximum-fallback-share must lie in [0, 1], got " + maximumFallbackShare);
		}
		List<String> excludedPrefixes = Arrays.stream(cmd.getOptionStrict("excluded-facility-prefixes").split(","))
				.map(String::trim).filter(prefix -> !prefix.isEmpty()).toList();

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new MatsimNetworkReader(scenario.getNetwork()).readFile(cmd.getOptionStrict("network-path"));
		new MatsimFacilitiesReader(scenario).readFile(cmd.getOptionStrict("input-facilities-path"));
		new PopulationReader(scenario).readFile(cmd.getOptionStrict("input-population-path"));

		LargeFacilityAccessLinkSelector selector = new LargeFacilityAccessLinkSelector(scenario.getNetwork(),
				minimumCapacityVehiclesPerHour, maximumDistanceMeters);
		logger.info("large facility access: {} candidate access links (minimum capacity {} veh/h, no motorway/trunk/ramp),"
				+ " maximum distance {} m, facilities of at least {} activities at 100 % (sample size {}), excluded"
				+ " prefixes {}", selector.getCandidateLinkCount(), minimumCapacityVehiclesPerHour, maximumDistanceMeters,
				minimumActivities, sampleSize, excludedPrefixes);
		Result result = new LargeFacilityAccessAssignment(scenario.getNetwork(), selector, sampleSize, minimumActivities,
				excludedPrefixes).run(scenario.getActivityFacilities(), scenario.getPopulation());

		writeSummary(Paths.get(cmd.getOptionStrict("summary-path")), result.decisions());
		logRates(result);
		checkFallbackShare(result, maximumFallbackShare);

		new FacilitiesWriter(scenario.getActivityFacilities()).write(cmd.getOptionStrict("output-facilities-path"));
		new PopulationWriter(scenario.getPopulation()).write(cmd.getOptionStrict("output-population-path"));
	}

	private static void logRates(Result result) {
		long adequate = result.count(Outcome.ADEQUATE);
		long moved = result.count(Outcome.MOVED);
		long fallback = result.count(Outcome.FALLBACK);
		long excluded = result.count(Outcome.EXCLUDED);
		long needing = moved + fallback;
		logger.info("large facility access: {} large facilities; already adequate {}, moved (primary) {}/{} ({} %),"
				+ " fallback to the current link {}/{} ({} %), excluded by prefix {}; {} leg routes removed",
				result.decisions().size(), adequate, moved, needing, percent(moved, needing), fallback, needing,
				percent(fallback, needing), excluded, result.removedRoutes());
		if (fallback > 0) {
			logger.warn("large facility access: {} large facilities found no eligible access link and keep their"
					+ " low-capacity link (see the summary file)", fallback);
		}
	}

	private static String percent(long count, long total) {
		return String.format(Locale.ROOT, "%.1f", total == 0 ? 0.0 : 100.0 * count / total);
	}

	/**
	 * Fails when the fallback share among facilities that needed a new link exceeds {@code maximumFallbackShare}.
	 */
	static void checkFallbackShare(Result result, double maximumFallbackShare) {
		long moved = result.count(Outcome.MOVED);
		long fallback = result.count(Outcome.FALLBACK);
		long needing = moved + fallback;
		if (needing == 0) {
			return;
		}
		double share = (double) fallback / needing;
		if (share > maximumFallbackShare) {
			throw new IllegalStateException(String.format(Locale.ROOT,
					"large facility access: %d of %d large facilities on low-capacity links (%.1f %%) found no eligible"
							+ " access link, above the allowed %.1f %%. Check the network CRS, the minimum capacity and"
							+ " the maximum distance.",
					fallback, needing, 100.0 * share, 100.0 * maximumFallbackShare));
		}
	}

	private static void writeSummary(Path path, List<Decision> decisions) {
		try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			writer.write(SUMMARY_HEADER);
			writer.write("\n");
			for (Decision decision : decisions) {
				writer.write(String.join(";", decision.facilityId().toString(),
						Integer.toString(decision.activitiesInSample()), format(decision.activities100Percent()),
						decision.oldLinkId().toString(), format(decision.oldLinkCapacityVehiclesPerHour()),
						decision.outcome().name().toLowerCase(Locale.ROOT),
						decision.newLinkId() == null ? "" : decision.newLinkId().toString(),
						decision.newLinkCapacityVehiclesPerHour() == null ? "" : format(decision.newLinkCapacityVehiclesPerHour()),
						decision.distanceMeters() == null ? "" : format(decision.distanceMeters())));
				writer.write("\n");
			}
		} catch (IOException e) {
			throw new UncheckedIOException("could not write the large facility access summary " + path, e);
		}
	}

	private static String format(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}
}
