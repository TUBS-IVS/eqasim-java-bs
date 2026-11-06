package org.eqasim.core.scenario.cutter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.core.components.travel_time.RecordedTravelTime;
import org.eqasim.core.misc.Constants.TransitCutMode;
import org.eqasim.core.misc.InjectorBuilder;
import org.eqasim.core.scenario.cutter.extent.ScenarioExtent;
import org.eqasim.core.scenario.cutter.extent.ShapeScenarioExtent;
import org.eqasim.core.scenario.cutter.facilities.CleanHomeFacilities;
import org.eqasim.core.scenario.cutter.facilities.FacilitiesCutter;
import org.eqasim.core.scenario.cutter.network.MinimumNetworkFinder;
import org.eqasim.core.scenario.cutter.network.NetworkCutter;
import org.eqasim.core.scenario.cutter.network.RoadNetwork;
import org.eqasim.core.scenario.cutter.outside.OutsideActivityAdapter;
import org.eqasim.core.scenario.cutter.population.CleanHouseholds;
import org.eqasim.core.scenario.cutter.population.CleanVehicles;
import org.eqasim.core.scenario.cutter.population.PopulationCutter;
import org.eqasim.core.scenario.cutter.population.PopulationCutterModule;
import org.eqasim.core.scenario.cutter.population.trips.NetworkTripCrossingStatistics;
import org.eqasim.core.scenario.cutter.population.trips.TransitTripProcessor;
import org.eqasim.core.scenario.cutter.population.RemoveEmptyPlans;
import org.eqasim.core.scenario.cutter.transit.TransitHubStops;
import org.eqasim.core.scenario.cutter.transit.TransitScheduleCutter;
import org.eqasim.core.scenario.cutter.transit.TransitScheduleCutter.KeptRange;
import org.eqasim.core.scenario.cutter.transit.TransitScheduleCutter.StopBreakdown;
import org.eqasim.core.scenario.cutter.transit.TransitVehiclesCutter;
import org.eqasim.core.scenario.routing.PopulationRouter;
import org.eqasim.core.scenario.routing.PopulationRouterModule;
import org.eqasim.core.scenario.validation.ScenarioValidator;
import org.eqasim.core.scenario.validation.VehiclesValidator;
import org.eqasim.core.simulation.EqasimConfigurator;
import org.eqasim.core.simulation.termination.EqasimTerminationConfigGroup;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.timing.TimeInterpretationModule;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import com.google.inject.Injector;

public class RunScenarioCutter {
	private final static Logger log = LogManager.getLogger(RunScenarioCutter.class);

	/** How pt trips are cut: {@code extent} (default, upstream) or {@code kept-stops} (eqasim-bs#574). */
	public static final String TRANSIT_CUT_MODE = "transit-cut-mode";
	/** UTF-8 file with one hub stop name per line; only with {@code kept-stops}. */
	public static final String TRANSIT_HUB_STOP_NAMES_PATH = "transit-hub-stop-names-path";

	public static final Collection<String> REQUIRED_ARGS = Set.of("config-path", "output-path", "extent-path");
	public static final Collection<String> OPTIONAL_ARGS = Set.of("threads", "prefix", "extent-attribute",
			"extent-value", "plans-path", "events-path", "skip-routing", TRANSIT_CUT_MODE,
			TRANSIT_HUB_STOP_NAMES_PATH, EqasimConfigurator.CONFIGURATOR);

	/**
	 * The transit cut mode of the command line; refuses a hub stop names file outside the kept-stops mode, which
	 * would otherwise be ignored silently.
	 */
	public static TransitCutMode parseTransitCutMode(CommandLine cmd) {
		TransitCutMode mode = TransitCutMode.fromOption(cmd.getOption(TRANSIT_CUT_MODE).orElse("extent"));

		if (mode != TransitCutMode.KEPT_STOPS && cmd.hasOption(TRANSIT_HUB_STOP_NAMES_PATH)) {
			throw new IllegalArgumentException("--" + TRANSIT_HUB_STOP_NAMES_PATH + " needs --" + TRANSIT_CUT_MODE
					+ " kept-stops (the " + mode.getOption() + " mode cuts without hubs)");
		}

		return mode;
	}

	private static int countRoutes(TransitSchedule schedule) {
		return schedule.getTransitLines().values().stream().mapToInt(line -> line.getRoutes().size()).sum();
	}

	static public void main(String[] args) throws ConfigurationException, IOException, InterruptedException, ExecutionException {
		CommandLine cmd = new CommandLine.Builder(args) //
				.requireOptions(REQUIRED_ARGS) //
				.allowOptions(OPTIONAL_ARGS) //
				.build();

		// Load some configuration
		String prefix = cmd.getOption("prefix").orElse("");
		int numberOfThreads = cmd.getOption("threads").map(Integer::parseInt)
				.orElse(Runtime.getRuntime().availableProcessors());
		TransitCutMode transitCutMode = parseTransitCutMode(cmd);

		File outputDirectory = new File(cmd.getOptionStrict("output-path")).getAbsoluteFile();
		ScenarioWriter.checkOutputDirectory(outputDirectory);

		// Load scenario extent
		File extentPath = new File(cmd.getOptionStrict("extent-path"));
		Optional<String> extentAttribute = cmd.getOption("extent-attribute");
		Optional<String> extentValue = cmd.getOption("extent-value");
		ScenarioExtent extent = new ShapeScenarioExtent.Builder(extentPath, extentAttribute, extentValue).build();

		// Load scenario
		EqasimConfigurator configurator = EqasimConfigurator.getInstance(cmd);
		Config config = ConfigUtils.loadConfig(cmd.getOptionStrict("config-path"));
		configurator.updateConfig(config);
		config.removeModule(EqasimTerminationConfigGroup.GROUP_NAME);
		cmd.applyConfiguration(config);

		VehiclesValidator.validate(config);

		Optional<String> plansPath = cmd.getOption("plans-path");

		if (plansPath.isPresent()) {
			File plansFile = new File(plansPath.get());

			if (!plansFile.exists()) {
				throw new IllegalStateException("Plans file does not exist: " + plansPath);
			} else {
				config.plans().setInputFile(plansFile.getAbsolutePath());
			}
		}

		Scenario scenario = ScenarioUtils.createScenario(config);
		configurator.configureScenario(scenario);
		ScenarioUtils.loadScenario(scenario);

		// Check validity before cutting
		ScenarioValidator scenarioValidator = new ScenarioValidator();
		scenarioValidator.checkScenario(scenario);

		// Prepare road network
		RoadNetwork roadNetwork = new RoadNetwork(scenario.getNetwork());

		// Optionally, load travel time
		Optional<RecordedTravelTime> travelTime = Optional.empty();

		if (cmd.hasOption("events-path")) {
			travelTime = Optional.of(RecordedTravelTime.readFromEvents( //
					new File(cmd.getOptionStrict("events-path")), roadNetwork, config));
		}

		// Prepare the transit cut (hubs and dry pass in the kept-stops mode, eqasim-bs#574)
		TransitCut transitCut = prepareTransitCut(cmd, transitCutMode, scenario.getTransitSchedule(), extent);

		// Cut population
		// TODO Check if we can remove stuff
		Injector populationCutterInjector = new InjectorBuilder(scenario, configurator) //
				.addOverridingModule(new PopulationCutterModule(extent, numberOfThreads, 40,
						cmd.getOption("events-path"), transitCutMode, transitCut.keptRanges)) //
				.addOverridingModule(new CutterTravelTimeModule(travelTime)) //
				.addOverridingModule(new TimeInterpretationModule()) //
				.build();

		PopulationCutter populationCutter = populationCutterInjector.getInstance(PopulationCutter.class);
		populationCutter.run(scenario.getPopulation());
		populationCutterInjector.getInstance(NetworkTripCrossingStatistics.class).log();

		transitCut.logCollapseCounts(populationCutterInjector);

		// ... and remove empty plans
		RemoveEmptyPlans removeEmptyPlans = new RemoveEmptyPlans();
		removeEmptyPlans.run(scenario.getPopulation());

		// ... and make outside activities consistent
		OutsideActivityAdapter outsideActivityAdapter = new OutsideActivityAdapter(roadNetwork);
		outsideActivityAdapter.run(scenario.getPopulation(), scenario.getActivityFacilities());

		// ... and make households consistent
		CleanHouseholds cleanHouseholds = new CleanHouseholds(scenario.getPopulation());
		cleanHouseholds.run(scenario.getHouseholds());

		// .. and make vehicles consistent
		CleanVehicles cleanVehicles = new CleanVehicles(scenario.getPopulation());
		cleanVehicles.run(scenario.getVehicles());

		// Cut transit
		transitCut.cutSchedule(scenario.getTransitSchedule());

		TransitVehiclesCutter transitVehiclesCutter = new TransitVehiclesCutter(scenario.getTransitSchedule());
		transitVehiclesCutter.run(scenario.getTransitVehicles());

		// Cut facilities
		CleanHomeFacilities cleanHomeFacilities = new CleanHomeFacilities(scenario.getPopulation());
		cleanHomeFacilities.run(scenario.getActivityFacilities());

		FacilitiesCutter facilitiesCutter = new FacilitiesCutter(extent, scenario.getPopulation());
		facilitiesCutter.run(scenario.getActivityFacilities(), true);

		// Cut network
		MinimumNetworkFinder minimumNetworkFinder = new MinimumNetworkFinder(extent, roadNetwork, numberOfThreads, 20);
		NetworkCutter networkCutter = new NetworkCutter(extent, scenario, minimumNetworkFinder);
		networkCutter.run(scenario.getNetwork());

		// "Cut" config
		// (we need to reload it, because it has become locked at this point)
		config = ConfigUtils.loadConfig(cmd.getOptionStrict("config-path"));
		configurator.updateConfig(config);
		cmd.applyConfiguration(config);
		ConfigCutter configCutter = new ConfigCutter(prefix);
		configCutter.run(config);

		// Final routing
		// TODO: Check if we can remove stuff
		Injector routingInjector = new InjectorBuilder(scenario, configurator) //
				.addOverridingModule(new PopulationRouterModule(numberOfThreads, 100, false)) //
				.addOverridingModule(new CutterTravelTimeModule(travelTime)) //
				.addOverridingModule(new TimeInterpretationModule()) //
				.build();

		boolean skipRouting = Boolean.parseBoolean(cmd.getOption("skip-routing").orElse("false"));

		if (!skipRouting) {
			PopulationRouter router = routingInjector.getInstance(PopulationRouter.class);
			router.run(scenario.getPopulation());
			// Check validity after cutting
			scenarioValidator.checkScenario(scenario);
		}

		// Write scenario
		ScenarioWriter scenarioWriter = new ScenarioWriter(config, scenario, prefix);
		scenarioWriter.run(outputDirectory);

		// Summarise the transit cut after the final routing, which decides whether a collapsed trip walks
		writeTransitCutSummary(transitCut, scenario, skipRouting, outputDirectory, prefix);
	}

	/**
	 * The transit cut of one run: the schedule cutter (with the hubs in the kept-stops mode), the kept stop ranges of
	 * its dry pass (kept-stops mode only, empty otherwise) and the counts the summary needs.
	 */
	static private class TransitCut {
		final TransitCutMode mode;
		final TransitScheduleCutter cutter;
		final Map<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> keptRanges;

		Map<String, Long> collapseCounts = Map.of();
		int routesBeforeCut;
		int routesKept;

		TransitCut(TransitCutMode mode, TransitScheduleCutter cutter,
				Map<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> keptRanges) {
			this.mode = mode;
			this.cutter = cutter;
			this.keptRanges = keptRanges;
		}

		boolean isKeptStops() {
			return mode == TransitCutMode.KEPT_STOPS;
		}

		/** Logs the processor's collapse counts as rates (kept-stops mode) and keeps them for the summary. */
		void logCollapseCounts(Injector populationCutterInjector) {
			if (!isKeptStops()) {
				return;
			}

			collapseCounts = populationCutterInjector.getInstance(TransitTripProcessor.class).getCollapseCounts();
			long withCrossings = collapseCounts.get(TransitTripProcessor.TRIPS_WITH_CROSSINGS);
			log.info("[RunScenarioCutter] pt trips with crossings of the kept timetable: " + withCrossings);

			for (String collapseCase : TransitTripProcessor.COLLAPSE_CASES) {
				log.info("[RunScenarioCutter] collapsed " + collapseCase + " "
						+ TransitCutSummary.rate(collapseCounts.get(collapseCase), withCrossings));
			}
		}

		/**
		 * Cuts the schedule with the same cutter as the dry pass; in the kept-stops mode it fails if the cut keeps a
		 * different number of routes than the dry pass that the population was cut against.
		 */
		void cutSchedule(TransitSchedule schedule) {
			routesBeforeCut = countRoutes(schedule);
			cutter.run(schedule);
			routesKept = countRoutes(schedule);

			if (isKeptStops() && routesKept != countRanges(keptRanges)) {
				throw new IllegalStateException("The schedule cut kept " + routesKept + " routes, its dry pass "
						+ countRanges(keptRanges) + ": the population was cut against a different timetable");
			}
		}
	}

	/**
	 * In the kept-stops mode: reads and resolves the hub stop names (none without the option) and runs the dry pass
	 * of the schedule cut on the still uncut schedule. In the extent mode: a cutter without hubs and no ranges.
	 */
	private static TransitCut prepareTransitCut(CommandLine cmd, TransitCutMode mode, TransitSchedule schedule,
			ScenarioExtent extent) throws IOException, ConfigurationException {
		if (mode != TransitCutMode.KEPT_STOPS) {
			return new TransitCut(mode, new TransitScheduleCutter(extent, Set.of()), Map.of());
		}

		List<String> hubNames = cmd.hasOption(TRANSIT_HUB_STOP_NAMES_PATH)
				? TransitHubStops.readNames(Path.of(cmd.getOptionStrict(TRANSIT_HUB_STOP_NAMES_PATH)))
				: List.of();
		Set<Id<TransitStopFacility>> hubStopIds = TransitHubStops.resolve(schedule, hubNames, extent);

		TransitScheduleCutter cutter = new TransitScheduleCutter(extent, hubStopIds);
		Map<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> keptRanges = cutter.keptStopRanges(schedule);
		log.info("[RunScenarioCutter] transit cut mode kept-stops: " + hubStopIds.size()
				+ " hub stop facilities, kept routes "
				+ TransitCutSummary.rate(countRanges(keptRanges), countRoutes(schedule)));

		return new TransitCut(mode, cutter, keptRanges);
	}

	/**
	 * In the kept-stops mode: collects the transit cut summary from the routed population, logs it and writes
	 * {@code <prefix>transit_cut_summary.csv} into the output directory. Nothing in the extent mode.
	 */
	private static void writeTransitCutSummary(TransitCut transitCut, Scenario scenario, boolean skipRouting,
			File outputDirectory, String prefix) throws IOException {
		if (!transitCut.isKeptStops()) {
			return;
		}

		if (skipRouting) {
			log.warn("[RunScenarioCutter] routing skipped: the walked counts of the transit cut summary are 0");
		}

		StopBreakdown stops = transitCut.cutter.classify(scenario.getTransitSchedule().getFacilities().values());
		TransitCutSummary summary = TransitCutSummary.collect(scenario.getPopulation(), transitCut.collapseCounts,
				stops.inside(), stops.hub(), stops.otherOutside(), transitCut.routesKept,
				transitCut.routesBeforeCut - transitCut.routesKept);
		summary.log();

		File summaryFile = new File(outputDirectory, prefix + "transit_cut_summary.csv");
		summary.write(summaryFile.toPath());
		log.info("[RunScenarioCutter] wrote " + summaryFile);
	}

	private static int countRanges(Map<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> keptRanges) {
		return keptRanges.values().stream().mapToInt(Map::size).sum();
	}
}
