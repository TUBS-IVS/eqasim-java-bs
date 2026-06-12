package org.eqasim.braunschweig.scenario;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.braunschweig.BraunschweigConfigurator;
import org.eqasim.braunschweig.mode_choice.BraunschweigModeChoiceModule;
import org.eqasim.core.components.config.EqasimConfigGroup;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings;
import org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams;
import org.matsim.core.config.groups.ScoringConfigGroup.ModeParams;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

/**
 * Reads the sampled freight-trips CSV (written by the Python injection hook,
 * already sampled at the pipeline sampling rate) and merges the agents into a
 * prepared eqasim scenario: builds one freight person per row (freight_start ->
 * truck leg -> freight_end), one heavy_truck vehicle each, adds the "truck"
 * network mode to all car links, adapts the config, and writes
 * population/vehicles/network/config back in place plus a summary CSV.
 *
 * Sampling already happened upstream in Python, so this is a deterministic
 * writer (no RNG here).
 */
public class RunInjectFreight {
	private static final Logger logger = LogManager.getLogger(RunInjectFreight.class);

	private static final String FREIGHT_SUBPOPULATION = "freight";
	private static final String TRUCK_MODE = "truck";
	private static final String TRUCK_VEHICLE_TYPE = "heavy_truck";
	private static final String ACTIVITY_START = "freight_start";
	private static final String ACTIVITY_END = "freight_end";
	private static final String EXPECTED_HEADER =
			"person_id;origin_x;origin_y;destination_x;destination_y;departure_time;trip_type";

	public static void main(String[] args) throws ConfigurationException, IOException {
		CommandLine cmd = new CommandLine.Builder(args)
				.requireOptions("config-path", "freight-csv-path", "summary-path")
				.allowOptions("truck-pce", "truck-max-velocity-kmh")
				.build();

		double pce = cmd.getOption("truck-pce").map(Double::parseDouble).orElse(3.5);
		double maxVelocityKmh = cmd.getOption("truck-max-velocity-kmh").map(Double::parseDouble).orElse(80.0);

		// Load the config with all eqasim/MATSim config groups registered (the
		// BraunschweigConfigurator registers them), mirroring ConfigAdapter: the
		// config-group types must be known before loadScenario reads the file.
		BraunschweigConfigurator configurator = new BraunschweigConfigurator(cmd);
		Config config = ConfigUtils.loadConfig(cmd.getOptionStrict("config-path"));
		configurator.updateConfig(config);
		Scenario scenario = ScenarioUtils.loadScenario(config);
		Population population = scenario.getPopulation();
		PopulationFactory factory = population.getFactory();

		VehicleType truckType = scenario.getVehicles().getFactory()
				.createVehicleType(Id.create(TRUCK_VEHICLE_TYPE, VehicleType.class));
		truckType.setPcuEquivalents(pce);
		truckType.setMaximumVelocity(maxVelocityKmh / 3.6);
		truckType.setLength(16.5);
		truckType.setWidth(2.55);
		truckType.setNetworkMode(TRUCK_MODE);
		scenario.getVehicles().addVehicleType(truckType);

		Path csvPath = Paths.get(cmd.getOptionStrict("freight-csv-path"));
		List<String> lines = Files.readAllLines(csvPath, StandardCharsets.UTF_8);
		if (lines.isEmpty()) {
			throw new IllegalStateException("freight CSV is empty: " + csvPath);
		}
		if (!EXPECTED_HEADER.equals(lines.get(0).trim())) {
			throw new IllegalStateException(
					"unexpected freight CSV header.\n  expected: " + EXPECTED_HEADER + "\n  got:      " + lines.get(0).trim());
		}

		Map<String, Long> tripTypeCounts = new TreeMap<>();
		int injected = 0;
		for (int i = 1; i < lines.size(); i++) {
			String line = lines.get(i).trim();
			if (line.isEmpty()) {
				continue;
			}
			String[] f = line.split(";", -1);
			if (f.length != 7) {
				throw new IllegalStateException("freight CSV line " + (i + 1) + " has " + f.length
						+ " fields, expected 7: " + line);
			}
			String personId = f[0];
			Coord origin = new Coord(Double.parseDouble(f[1]), Double.parseDouble(f[2]));
			Coord destination = new Coord(Double.parseDouble(f[3]), Double.parseDouble(f[4]));
			double departureTime = Double.parseDouble(f[5]);
			String tripType = f[6];

			Id<Person> id = Id.createPersonId(personId);
			if (population.getPersons().containsKey(id)) {
				throw new IllegalStateException("freight person id collides with resident: " + id);
			}
			Person person = factory.createPerson(id);
			person.getAttributes().putAttribute("subpopulation", FREIGHT_SUBPOPULATION);
			person.getAttributes().putAttribute("trip_type", tripType);

			Plan plan = factory.createPlan();
			Activity start = factory.createActivityFromCoord(ACTIVITY_START, origin);
			start.setEndTime(departureTime);
			plan.addActivity(start);
			plan.addLeg(factory.createLeg(TRUCK_MODE));
			plan.addActivity(factory.createActivityFromCoord(ACTIVITY_END, destination));
			person.addPlan(plan);
			population.addPerson(person);

			Id<Vehicle> vehicleId = Id.create(personId + ":" + TRUCK_MODE, Vehicle.class);
			scenario.getVehicles().addVehicle(
					scenario.getVehicles().getFactory().createVehicle(vehicleId, truckType));
			VehicleUtils.insertVehicleIdsIntoPersonAttributes(person, Map.of(TRUCK_MODE, vehicleId));

			tripTypeCounts.merge(tripType, 1L, Long::sum);
			injected++;
		}

		logger.info("freight injection: injected {} agents from {}", injected, csvPath);
		for (Map.Entry<String, Long> entry : tripTypeCounts.entrySet()) {
			logger.info("  trip type {}: injected {}", entry.getKey(), entry.getValue());
		}

		int updatedLinks = 0;
		for (Link link : scenario.getNetwork().getLinks().values()) {
			if (link.getAllowedModes().contains(TransportMode.car)
					&& !link.getAllowedModes().contains(TRUCK_MODE)) {
				Set<String> modes = new HashSet<>(link.getAllowedModes());
				modes.add(TRUCK_MODE);
				link.setAllowedModes(modes);
				updatedLinks++;
			}
		}
		logger.info("freight injection: added '{}' mode to {} car links", TRUCK_MODE, updatedLinks);

		adaptConfig(config);

		String configPath = cmd.getOptionStrict("config-path");
		java.io.File configDir = new java.io.File(configPath).getAbsoluteFile().getParentFile();
		new PopulationWriter(population)
				.write(new java.io.File(configDir, config.plans().getInputFile()).toString());
		new MatsimVehicleWriter(scenario.getVehicles())
				.writeFile(new java.io.File(configDir, config.vehicles().getVehiclesFile()).toString());
		new NetworkWriter(scenario.getNetwork())
				.write(new java.io.File(configDir, config.network().getInputFile()).toString());
		new ConfigWriter(config).write(configPath);

		try (BufferedWriter writer = Files.newBufferedWriter(
				Paths.get(cmd.getOptionStrict("summary-path")), StandardCharsets.UTF_8)) {
			writer.write("trip_type;injected_trips\n");
			for (Map.Entry<String, Long> entry : tripTypeCounts.entrySet()) {
				writer.write(String.format("%s;%d%n", entry.getKey(), entry.getValue()));
			}
		}
	}

	private static void adaptConfig(Config config) {
		Set<String> mainModes = new HashSet<>(config.qsim().getMainModes());
		mainModes.add(TRUCK_MODE);
		config.qsim().setMainModes(mainModes);

		Set<String> networkModes = new HashSet<>(config.routing().getNetworkModes());
		networkModes.add(TRUCK_MODE);
		config.routing().setNetworkModes(networkModes);

		for (String activity : new String[] { ACTIVITY_START, ACTIVITY_END }) {
			if (config.scoring().getActivityParams(activity) == null) {
				ActivityParams params = new ActivityParams(activity);
				params.setTypicalDuration(3600.0);
				params.setScoringThisActivityAtAll(false);
				config.scoring().addActivityParams(params);
			}
		}
		if (config.scoring().getModes().get(TRUCK_MODE) == null) {
			ModeParams truckParams = new ModeParams(TRUCK_MODE);
			truckParams.setMarginalUtilityOfTraveling(-1.0);
			config.scoring().addModeParams(truckParams);
		}

		// KeepLastSelected mirrors the resident strategy mix (DiscreteModeChoice +
		// KeepLastSelected). A score-based selector (e.g. ChangeExpBeta) is NOT
		// allowed here: eqasim enforces mode-choice-in-the-loop and the DMC
		// ModeChoiceInTheLoopChecker aborts on ANY enabled ChangeExpBeta strategy,
		// regardless of its subpopulation (verified on a real 1% run). Freight
		// agents have a single plan and a single mode, so a score-based selector
		// would add nothing anyway; route updates come from ReRoute below.
		StrategySettings selector = new StrategySettings();
		selector.setStrategyName("KeepLastSelected");
		selector.setSubpopulation(FREIGHT_SUBPOPULATION);
		selector.setWeight(0.95);
		config.replanning().addStrategySettings(selector);

		StrategySettings reroute = new StrategySettings();
		reroute.setStrategyName("ReRoute");
		reroute.setSubpopulation(FREIGHT_SUBPOPULATION);
		reroute.setWeight(0.05);
		config.replanning().addStrategySettings(reroute);

		EqasimConfigGroup eqasimConfig = EqasimConfigGroup.get(config);
		eqasimConfig.setEstimator(TRUCK_MODE, BraunschweigModeChoiceModule.FREIGHT_TRUCK_ESTIMATOR_NAME);
	}
}
