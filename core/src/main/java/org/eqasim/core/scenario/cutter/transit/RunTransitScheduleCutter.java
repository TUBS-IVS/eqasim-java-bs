package org.eqasim.core.scenario.cutter.transit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.core.scenario.cutter.extent.ScenarioExtent;
import org.eqasim.core.scenario.cutter.extent.ShapeScenarioExtent;
import org.eqasim.core.scenario.cutter.transit.TransitScheduleCutter.KeptRange;
import org.eqasim.core.scenario.cutter.transit.TransitScheduleCutter.StopBreakdown;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/**
 * Cuts a transit schedule file to an extent and its hub stations, without network, vehicles or population: the
 * routes that touch the extent are kept from their first to their last anchor (a stop inside the extent or a
 * hub), see {@link TransitScheduleCutter}. Writes the cut schedule and a table of the matched hub facilities.
 */
public class RunTransitScheduleCutter {
	private final static Logger log = LogManager.getLogger(RunTransitScheduleCutter.class);

	public static final Collection<String> REQUIRED_ARGS = Set.of("schedule-path", "extent-path",
			"output-schedule-path", "output-hubs-path");
	public static final Collection<String> OPTIONAL_ARGS = Set.of("hub-stop-names-path", "extent-attribute",
			"extent-value");

	static final String HUB_TABLE_HEADER = "name,stop_id,x,y,inside_extent";

	/** One matched hub facility: the name it was matched by, its id and coordinate, and whether it is inside. */
	public record HubStop(String name, Id<TransitStopFacility> stopId, double x, double y, boolean insideExtent) {
	}

	/**
	 * The outcome of a cut: the matched hub facilities (also those whose route is dropped), the kept stop
	 * facilities by kind (inside the extent, hub outside, other outside) and the routes kept and dropped.
	 */
	public record CutSummary(List<HubStop> hubStops, int stopsInside, int stopsHub, int stopsOtherOutside,
			int routesKept, int routesDropped) {
		public Set<Id<TransitStopFacility>> hubStopIds() {
			Set<Id<TransitStopFacility>> ids = new LinkedHashSet<>();
			hubStops.forEach(hub -> ids.add(hub.stopId()));
			return ids;
		}
	}

	/**
	 * Cuts the schedule in place. Fails if a hub name matches no facility or if no route is kept.
	 */
	public static CutSummary cut(TransitSchedule schedule, ScenarioExtent extent, List<String> hubNames) {
		Set<Id<TransitStopFacility>> hubStopIds = TransitHubStops.resolve(schedule, hubNames, extent);

		// Snapshot of the matched facilities before the cut, which removes the facilities of dropped routes
		List<HubStop> hubStops = new ArrayList<>();
		for (String name : new LinkedHashSet<>(hubNames)) {
			schedule.getFacilities().values().stream() //
					.filter(facility -> name.equals(facility.getName()) && hubStopIds.contains(facility.getId())) //
					.sorted((a, b) -> a.getId().toString().compareTo(b.getId().toString())) //
					.forEach(facility -> hubStops.add(new HubStop(name, facility.getId(), facility.getCoord().getX(),
							facility.getCoord().getY(), extent.isInside(facility.getCoord()))));
		}

		TransitScheduleCutter cutter = new TransitScheduleCutter(extent, hubStopIds);

		int routesBefore = countRoutes(schedule);
		Map<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> ranges = cutter.keptStopRanges(schedule);
		cutter.run(schedule);
		int routesKept = countRoutes(schedule);

		if (routesKept == 0) {
			throw new IllegalStateException("The cut keeps no route: the extent contains no stop served by a route");
		}

		StopBreakdown breakdown = cutter.classify(schedule.getFacilities().values());
		CutSummary summary = new CutSummary(hubStops, breakdown.inside(), breakdown.hub(), breakdown.otherOutside(),
				routesKept, routesBefore - routesKept);

		log.info(String.format(Locale.ROOT,
				"[RunTransitScheduleCutter] kept stops %d: inside %d, hub %d, other outside %d; routes kept %d/%d (%.1f %%), dropped %d; dry pass named %d routes",
				breakdown.total(), summary.stopsInside(), summary.stopsHub(), summary.stopsOtherOutside(), routesKept,
				routesBefore, 100.0 * routesKept / routesBefore, summary.routesDropped(), countRanges(ranges)));

		return summary;
	}

	private static int countRoutes(TransitSchedule schedule) {
		int count = 0;
		for (TransitLine line : schedule.getTransitLines().values()) {
			count += line.getRoutes().size();
		}
		return count;
	}

	private static int countRanges(Map<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> ranges) {
		return ranges.values().stream().mapToInt(Map::size).sum();
	}

	/**
	 * Writes the cut schedule and the hub table (UTF-8, header {@value #HUB_TABLE_HEADER}, one row per matched
	 * hub facility, numbers with a dot and three decimals, LF line ends).
	 */
	public static void write(TransitSchedule schedule, CutSummary summary, Path outputSchedule, Path outputHubs)
			throws IOException {
		new TransitScheduleWriter(schedule).writeFile(outputSchedule.toString());

		StringBuilder csv = new StringBuilder(HUB_TABLE_HEADER).append('\n');
		for (HubStop hub : summary.hubStops()) {
			csv.append(escape(hub.name())).append(',') //
					.append(escape(hub.stopId().toString())).append(',') //
					.append(String.format(Locale.ROOT, "%.3f", hub.x())).append(',') //
					.append(String.format(Locale.ROOT, "%.3f", hub.y())).append(',') //
					.append(hub.insideExtent()).append('\n');
		}

		Files.writeString(outputHubs, csv.toString(), StandardCharsets.UTF_8);
	}

	private static String escape(String value) {
		if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
			return "\"" + value.replace("\"", "\"\"") + "\"";
		}
		return value;
	}

	private static File requireFile(CommandLine cmd, String option) throws ConfigurationException {
		File file = new File(cmd.getOptionStrict(option));

		if (!file.isFile()) {
			throw new IllegalStateException("Input file of --" + option + " does not exist: " + file);
		}

		return file;
	}

	static public void main(String[] args) throws ConfigurationException, IOException {
		CommandLine cmd = new CommandLine.Builder(args) //
				.requireOptions(REQUIRED_ARGS) //
				.allowOptions(OPTIONAL_ARGS) //
				.build();

		File schedulePath = requireFile(cmd, "schedule-path");
		File extentPath = requireFile(cmd, "extent-path");
		Optional<String> extentAttribute = cmd.getOption("extent-attribute");
		Optional<String> extentValue = cmd.getOption("extent-value");

		List<String> hubNames = new ArrayList<>();
		if (cmd.hasOption("hub-stop-names-path")) {
			hubNames = TransitHubStops.readNames(requireFile(cmd, "hub-stop-names-path").toPath());
		}
		log.info(String.format(Locale.ROOT, "[RunTransitScheduleCutter] %d hub stop names", hubNames.size()));

		ScenarioExtent extent = new ShapeScenarioExtent.Builder(extentPath, extentAttribute, extentValue).build();

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new TransitScheduleReader(scenario).readFile(schedulePath.getPath());
		TransitSchedule schedule = scenario.getTransitSchedule();

		CutSummary summary = cut(schedule, extent, hubNames);

		write(schedule, summary, new File(cmd.getOptionStrict("output-schedule-path")).toPath(),
				new File(cmd.getOptionStrict("output-hubs-path")).toPath());
	}
}
