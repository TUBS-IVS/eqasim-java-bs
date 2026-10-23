package org.eqasim.braunschweig.scenario;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.braunschweig.scenario.LargeSiteAccessAssignment.AccessGroupRecord;
import org.eqasim.braunschweig.scenario.LargeSiteAccessAssignment.Outcome;
import org.eqasim.braunschweig.scenario.LargeSiteAccessAssignment.Parameters;
import org.eqasim.braunschweig.scenario.LargeSiteAccessAssignment.Result;
import org.eqasim.braunschweig.scenario.LargeSiteAccessAssignment.SiteDecision;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
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
 * Road access of large sites (issue #465, ADR-0165): runs {@link LargeSiteAccessAssignment} on the prepared network,
 * facilities and population, between eqasim's {@code RunPreparation} and the population routing.
 *
 * <p>
 * Reads the parking-lot release ({@link ParkingLotReader}) and drops the lots outside the network's bounding box grown
 * by {@value #LOT_EXTENT_MARGIN_METERS} m (counted); none left fails the step (a file in another CRS or for another
 * region). Writes one summary row per large site ({@link #SUMMARY_HEADER}) and one row per access group
 * ({@link #GROUPS_HEADER}), logs the outcome rates, and then fails when (a) the share of in-scope sites without a
 * usable lot exceeds {@code maximum-no-lot-share} (the silent way back to the single-link rule) or (b) the share of
 * single-link fallbacks among the sites that needed a new link exceeds {@code maximum-fallback-share}. The facilities
 * and the population are written only after both checks (the output paths may equal the input paths). Deterministic.
 * </p>
 *
 * <p>
 * Options (all required; {@code matsim.simulation.prepare} of eqasim-bs passes them): {@code network-path},
 * {@code input-facilities-path}, {@code input-population-path}, {@code output-facilities-path},
 * {@code output-population-path}, {@code parking-lots-path}, {@code summary-path}, {@code groups-path},
 * {@code sample-size}, {@code minimum-activities}, {@code minimum-capacity-veh-per-h}, {@code maximum-distance-m}
 * (single-link rule), {@code maximum-fallback-share}, {@code excluded-facility-prefixes}, {@code parking-demand-share},
 * {@code parking-maximum-distance-m}, {@code lot-link-maximum-distance-m}, {@code maximum-no-lot-share},
 * {@code scope-work-share}, {@code scope-facility-prefixes} (prefix lists comma separated, may be empty).
 * </p>
 */
public final class RunAdaptLargeFacilityAccess {
	private static final Logger logger = LogManager.getLogger(RunAdaptLargeFacilityAccess.class);

	static final double LOT_EXTENT_MARGIN_METERS = 2000.0;

	static final String SUMMARY_HEADER = "site_key;x;y;facilities;activities_in_sample;activities_100pct;in_scope;outcome;"
			+ "old_link_id;old_link_capacity_veh_per_h;new_link_id;new_link_capacity_veh_per_h;distance_meters;lots_used;"
			+ "access_groups;spaces_used;coverage_ratio;spaces_weighted_distance_meters;maximum_lot_distance_meters";
	static final String GROUPS_HEADER = "site_key;group_number;link_id;link_capacity_veh_per_h;spaces;persons;lot_ids;"
			+ "facility_ids";

	private RunAdaptLargeFacilityAccess() {
	}

	public static void main(String[] args) throws ConfigurationException {
		CommandLine cmd = new CommandLine.Builder(args)
				.requireOptions("network-path", "input-facilities-path", "input-population-path", "output-facilities-path",
						"output-population-path", "parking-lots-path", "summary-path", "groups-path", "sample-size",
						"minimum-activities", "minimum-capacity-veh-per-h", "maximum-distance-m", "maximum-fallback-share",
						"excluded-facility-prefixes", "parking-demand-share", "parking-maximum-distance-m",
						"lot-link-maximum-distance-m", "maximum-no-lot-share", "scope-work-share", "scope-facility-prefixes")
				.build();
		double maximumFallbackShare = share(cmd, "maximum-fallback-share");
		double maximumNoLotShare = share(cmd, "maximum-no-lot-share");
		Parameters parameters = new Parameters(number(cmd, "sample-size"), number(cmd, "minimum-activities"),
				number(cmd, "minimum-capacity-veh-per-h"), number(cmd, "maximum-distance-m"),
				number(cmd, "parking-demand-share"), number(cmd, "parking-maximum-distance-m"),
				number(cmd, "lot-link-maximum-distance-m"), number(cmd, "scope-work-share"),
				prefixes(cmd, "scope-facility-prefixes"), prefixes(cmd, "excluded-facility-prefixes"));

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new MatsimNetworkReader(scenario.getNetwork()).readFile(cmd.getOptionStrict("network-path"));
		new MatsimFacilitiesReader(scenario).readFile(cmd.getOptionStrict("input-facilities-path"));
		new PopulationReader(scenario).readFile(cmd.getOptionStrict("input-population-path"));
		Path lotsPath = Paths.get(cmd.getOptionStrict("parking-lots-path"));
		List<ParkingLot> lots = lotsInsideNetwork(ParkingLotReader.read(lotsPath), scenario.getNetwork(), lotsPath);

		logger.info("large facility access: {} parking lots with {} spaces from {}; sites of at least {} activities at 100 % "
				+ "(sample size {}), in scope: work share >= {} or prefixes {}; lots nearest-first to {} x size within {} m, lot "
				+ "links >= {} veh/h within {} m; single-link rule >= {} veh/h within {} m; excluded prefixes {}", lots.size(),
				lots.stream().mapToLong(ParkingLot::spaces).sum(), lotsPath, parameters.minimumActivities(),
				parameters.sampleSize(), parameters.scopeWorkShare(), parameters.scopeFacilityPrefixes(),
				parameters.parkingDemandShare(), parameters.parkingMaximumDistanceMeters(),
				parameters.minimumCapacityVehiclesPerHour(), parameters.lotLinkMaximumDistanceMeters(),
				parameters.minimumCapacityVehiclesPerHour(), parameters.singleLinkMaximumDistanceMeters(),
				parameters.excludedFacilityPrefixes());
		Result result = new LargeSiteAccessAssignment(scenario.getNetwork(), parameters, lots)
				.run(scenario.getActivityFacilities(), scenario.getPopulation());

		writeSummary(Paths.get(cmd.getOptionStrict("summary-path")), result.sites());
		writeGroups(Paths.get(cmd.getOptionStrict("groups-path")), result.groups());
		logRates(result);
		checkBounds(result, maximumNoLotShare, maximumFallbackShare);

		new FacilitiesWriter(scenario.getActivityFacilities()).write(cmd.getOptionStrict("output-facilities-path"));
		new PopulationWriter(scenario.getPopulation()).write(cmd.getOptionStrict("output-population-path"));
	}

	private static double number(CommandLine cmd, String option) throws ConfigurationException {
		return Double.parseDouble(cmd.getOptionStrict(option));
	}

	private static double share(CommandLine cmd, String option) throws ConfigurationException {
		double value = number(cmd, option);
		if (!(value >= 0.0 && value <= 1.0)) {
			throw new IllegalArgumentException(option + " must lie in [0, 1], got " + value);
		}
		return value;
	}

	private static List<String> prefixes(CommandLine cmd, String option) throws ConfigurationException {
		return Arrays.stream(cmd.getOptionStrict(option).split(",")).map(String::trim).filter(text -> !text.isEmpty())
				.toList();
	}

	/** The lots inside the network's node bounding box grown by the margin; fails when none is left. */
	static List<ParkingLot> lotsInsideNetwork(List<ParkingLot> lots, Network network, Path source) {
		double minX = Double.POSITIVE_INFINITY;
		double minY = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY;
		double maxY = Double.NEGATIVE_INFINITY;
		for (Node node : network.getNodes().values()) {
			minX = Math.min(minX, node.getCoord().getX());
			minY = Math.min(minY, node.getCoord().getY());
			maxX = Math.max(maxX, node.getCoord().getX());
			maxY = Math.max(maxY, node.getCoord().getY());
		}
		List<ParkingLot> inside = new ArrayList<>();
		for (ParkingLot lot : lots) {
			double x = lot.coord().getX();
			double y = lot.coord().getY();
			if (x >= minX - LOT_EXTENT_MARGIN_METERS && x <= maxX + LOT_EXTENT_MARGIN_METERS
					&& y >= minY - LOT_EXTENT_MARGIN_METERS && y <= maxY + LOT_EXTENT_MARGIN_METERS) {
				inside.add(lot);
			}
		}
		int outside = lots.size() - inside.size();
		if (inside.isEmpty()) {
			throw new IllegalStateException(String.format(Locale.ROOT,
					"large facility access: none of the %d parking lots of %s lies inside the network extent (x %.0f..%.0f, "
							+ "y %.0f..%.0f) grown by %.0f m; the lots file is in another CRS (EPSG:25832 expected) or for "
							+ "another region", lots.size(), source, minX, maxX, minY, maxY, LOT_EXTENT_MARGIN_METERS));
		}
		if (outside > 0) {
			logger.warn("large facility access: {} of {} parking lots lie outside the network extent grown by {} m and are "
					+ "not used", outside, lots.size(), LOT_EXTENT_MARGIN_METERS);
		}
		return inside;
	}

	private static void logRates(Result result) {
		long sites = result.sites().size();
		long inScope = result.inScopeSites();
		long distributed = result.count(Outcome.DISTRIBUTED);
		long partial = result.count(Outcome.DISTRIBUTED_PARTIAL);
		long moved = result.count(Outcome.MOVED);
		long fallback = result.count(Outcome.FALLBACK);
		long lotsUsed = (long) result.lotsOnCapacityRule() + result.lotsOnNearestRule();
		logger.info("large facility access: {} large sites, {} in scope; distributed over parking lots (primary) {}/{} "
				+ "({} %), of them partly covered {}; in scope without a usable lot {}/{} ({} %); single-link rule: adequate {}, "
				+ "moved {}, fallback {}/{} ({} %); {} facilities with an excluded prefix would be large; {} access groups, {} "
				+ "sub-facilities, {} persons reassigned, {} routes removed; lot links on the >= capacity rule {}/{} ({} %), on "
				+ "the nearest rule {}, lots skipped without a link {}", sites, inScope, distributed + partial, inScope,
				percent(distributed + partial, inScope), partial, result.inScopeWithoutLot(), inScope,
				percent(result.inScopeWithoutLot(), inScope), result.count(Outcome.ADEQUATE), moved, fallback, moved + fallback,
				percent(fallback, moved + fallback), result.excludedLargeFacilities(), result.groups().size(),
				result.subFacilitiesCreated(), result.personsReassigned(), result.removedRoutes(), result.lotsOnCapacityRule(),
				lotsUsed, percent(result.lotsOnCapacityRule(), lotsUsed), result.lotsOnNearestRule(),
				result.lotsSkippedNoLink());
		if (fallback > 0) {
			logger.warn("large facility access: {} large sites found no eligible access link and keep their low-capacity link "
					+ "(see the summary file)", fallback);
		}
		if (partial > 0) {
			logger.warn("large facility access: {} in-scope sites found fewer spaces than wanted within the radius and use all "
					+ "lots in reach (coverage_ratio in the summary file)", partial);
		}
		if (result.inScopeWithoutLot() > 0) {
			logger.warn("large facility access: {} in-scope sites found no usable parking lot within the radius and took the "
					+ "single-link rule", result.inScopeWithoutLot());
		}
	}

	private static String percent(long count, long total) {
		return String.format(Locale.ROOT, "%.1f", total == 0 ? 0.0 : 100.0 * count / total);
	}

	static void checkBounds(Result result, double maximumNoLotShare, double maximumFallbackShare) {
		long inScope = result.inScopeSites();
		if (inScope > 0) {
			double share = (double) result.inScopeWithoutLot() / inScope;
			if (share > maximumNoLotShare) {
				throw new IllegalStateException(String.format(Locale.ROOT,
						"large facility access: %d of %d in-scope large sites (%.1f %%) found no usable parking lot within the "
								+ "radius, above the allowed %.1f %%. Check the parking-lot file (region, CRS) and the radius.",
						result.inScopeWithoutLot(), inScope, 100.0 * share, 100.0 * maximumNoLotShare));
			}
		}
		long moved = result.count(Outcome.MOVED);
		long fallback = result.count(Outcome.FALLBACK);
		if (moved + fallback > 0) {
			double share = (double) fallback / (moved + fallback);
			if (share > maximumFallbackShare) {
				throw new IllegalStateException(String.format(Locale.ROOT,
						"large facility access: %d of %d large sites on low-capacity links (%.1f %%) found no eligible access "
								+ "link, above the allowed %.1f %%. Check the network CRS, the minimum capacity and the maximum "
								+ "distance.", fallback, moved + fallback, 100.0 * share, 100.0 * maximumFallbackShare));
			}
		}
	}

	private static void writeSummary(Path path, List<SiteDecision> sites) {
		try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			writer.write(SUMMARY_HEADER);
			writer.write("\n");
			for (SiteDecision site : sites) {
				writer.write(String.join(";", site.siteKey(), format(site.coord().getX()), format(site.coord().getY()),
						Integer.toString(site.facilities()), Integer.toString(site.activitiesInSample()),
						format(site.activities100Percent()), Boolean.toString(site.inScope()),
						site.outcome().name().toLowerCase(Locale.ROOT), site.oldLinkId().toString(),
						format(site.oldLinkCapacityVehiclesPerHour()), text(site.newLinkId()),
						format(site.newLinkCapacityVehiclesPerHour()), format(site.distanceMeters()),
						Integer.toString(site.lotsUsed()), Integer.toString(site.accessGroups()),
						Integer.toString(site.spacesUsed()), String.format(Locale.ROOT, "%.3f", site.coverageRatio()),
						format(site.spacesWeightedDistanceMeters()), format(site.maximumLotDistanceMeters())));
				writer.write("\n");
			}
		} catch (IOException e) {
			throw new UncheckedIOException("could not write the large facility access summary " + path, e);
		}
	}

	private static void writeGroups(Path path, List<AccessGroupRecord> groups) {
		try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			writer.write(GROUPS_HEADER);
			writer.write("\n");
			for (AccessGroupRecord group : groups) {
				writer.write(String.join(";", group.siteKey(), Integer.toString(group.groupNumber()), group.linkId().toString(),
						format(group.linkCapacityVehiclesPerHour()), Integer.toString(group.spaces()),
						Integer.toString(group.persons()), String.join("|", group.lotIds()),
						String.join("|", group.facilityIds())));
				writer.write("\n");
			}
		} catch (IOException e) {
			throw new UncheckedIOException("could not write the large facility access groups " + path, e);
		}
	}

	private static String format(Double value) {
		return value == null ? "" : String.format(Locale.ROOT, "%.1f", value);
	}

	private static String text(Object value) {
		return value == null ? "" : value.toString();
	}
}
