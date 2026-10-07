package org.eqasim.braunschweig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.eqasim.braunschweig.mode_choice.BraunschweigModeChoiceModule;
import org.eqasim.braunschweig.mode_choice.costs.BraunschweigCarCostModel;
import org.eqasim.braunschweig.mode_choice.utilities.estimators.BraunschweigCarPassengerUtilityEstimator;
import org.eqasim.braunschweig.mode_choice.utilities.estimators.BraunschweigCarUtilityEstimator;
import org.eqasim.braunschweig.parking.ParkingConfigGroup;
import org.eqasim.braunschweig.parking.ParkingOutcome;
import org.eqasim.braunschweig.parking.ParkingOutcomeCounter;
import org.eqasim.braunschweig.parking.ParkingOutcomeReportListener;
import org.eqasim.braunschweig.parking.ParkingPopulationCheck;
import org.eqasim.braunschweig.parking.ParkingSearchTime;
import org.eqasim.braunschweig.parking.ParkingSearchTimeCheck;
import org.eqasim.braunschweig.parking.ParkingTariffs;
import org.eqasim.braunschweig.parking.ZoneParkingCarCostModel;
import org.eqasim.braunschweig.scenario.RunAdaptConfig;
import org.eqasim.core.components.config.EqasimConfigGroup;
import org.eqasim.core.scenario.config.GenerateConfig;
import org.eqasim.core.simulation.mode_choice.cost.CostModel;
import org.eqasim.core.simulation.mode_choice.utilities.UtilityEstimator;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.controler.listener.ControllerListener;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;

/**
 * Builds the real MATSim controller injector the way RunSimulation does, with and without an enabled
 * braunschweigParking module, and resolves the car cost model mode choice uses. Guards the controller start of a
 * zones run (for example a binding that collides with MATSim's own config-group bindings) and shows that the module
 * changes the car cost model only: the car and car passenger estimators and the pt cost model keep their bindings. A
 * broken tariff model stops Controler.run() before iteration 0.
 */
public class ParkingInjectorTest {
	private static final String TARIFFS_FILE = "parking_tariffs_2026-09-28.json";

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private Injector injector(boolean parkingEnabled) throws Exception {
		return controller(parkingEnabled, tariffModel -> {
		}).getInjector();
	}

	/** The injector of an enabled module whose config carries the given additional parameters, such as searchTimeFactor. */
	private Injector injector(Map<String, String> parkingParameters) throws Exception {
		return controller(true, tariffModel -> {
		}, parkingParameters).getInjector();
	}

	/**
	 * The controller of a prepared scenario (empty population and network) whose tariff model is the fixture changed by
	 * {@code tariffModelChange}; nothing is injected yet.
	 */
	private Controler controller(boolean parkingEnabled, Consumer<ObjectNode> tariffModelChange) throws Exception {
		return controller(parkingEnabled, tariffModelChange, Map.of());
	}

	private Controler controller(boolean parkingEnabled, Consumer<ObjectNode> tariffModelChange,
			Map<String, String> parkingParameters) throws Exception {
		return controller(parkingEnabled, tariffModelChange, parkingParameters, new String[0], config -> {
		});
	}

	/**
	 * The controller of {@link #controller(boolean, Consumer)} for a run with the given command-line options (such as
	 * --mode-choice-parameter:car.constantParkingSearchPenalty_min=3.0) and a change of the finished config, such as the path
	 * of a mode parameters file.
	 */
	private Controler controller(boolean parkingEnabled, Consumer<ObjectNode> tariffModelChange,
			Map<String, String> parkingParameters, String[] commandLineArguments, Consumer<Config> configChange)
			throws Exception {
		// The preparation stage writes the tariff model next to the prepared config; its path is config-relative.
		Path prepared = folder.newFolder("prepared").toPath();
		ObjectMapper mapper = new ObjectMapper();
		ObjectNode tariffModel = (ObjectNode) mapper
				.readTree(Path.of(getClass().getResource("/parking/parking_tariffs_fixture.json").toURI()).toFile());
		tariffModelChange.accept(tariffModel);
		mapper.writeValue(prepared.resolve(TARIFFS_FILE).toFile(), tariffModel);

		// The pipeline's config chain: RunGenerateConfig, RunAdaptConfig, the braunschweigParking module written by the
		// Python prepare stage, then RunSimulation's updateConfig on the loaded file.
		CommandLine cmd = new CommandLine.Builder(commandLineArguments).allowAnyOption(true).build();
		BraunschweigConfigurator configurator = new BraunschweigConfigurator(cmd);
		Config config = ConfigUtils.createConfig();
		configurator.updateConfig(config);
		new GenerateConfig(cmd, "", 0.01, 0, 1).run(config);
		RunAdaptConfig.adaptConfiguration(config, "");
		config.setContext(prepared.resolve("config.xml").toUri().toURL());

		if (parkingEnabled) {
			ConfigGroup raw = new ConfigGroup(ParkingConfigGroup.GROUP_NAME);
			raw.addParam("enabled", "true");
			raw.addParam("tariffsPath", TARIFFS_FILE);
			parkingParameters.forEach(raw::addParam);
			config.addModule(raw);
		}
		config.controller().setOutputDirectory(folder.newFolder("output").toString());
		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setLastIteration(0);
		configurator.updateConfig(config);
		configChange.accept(config);

		Scenario scenario = ScenarioUtils.createScenario(config);
		configurator.configureScenario(scenario);
		Controler controller = new Controler(scenario);
		configurator.configureController(controller);
		return controller;
	}

	private static Map<String, Provider<CostModel>> costModels(Injector injector) {
		return injector.getInstance(Key.get(new TypeLiteral<Map<String, Provider<CostModel>>>() {
		}));
	}

	private static Map<String, Provider<UtilityEstimator>> estimators(Injector injector) {
		return injector.getInstance(Key.get(new TypeLiteral<Map<String, Provider<UtilityEstimator>>>() {
		}));
	}

	private static CostModel carCostModel(Injector injector) {
		return injector.getInstance(Key.get(CostModel.class, Names.named(TransportMode.car)));
	}

	/** Whether the controller listeners, which Controler.run() builds before the startup event, include one of the type. */
	private static boolean hasListener(Injector injector, Class<? extends ControllerListener> type) {
		Set<ControllerListener> listeners = injector.getInstance(Key.get(new TypeLiteral<Set<ControllerListener>>() {
		}));
		return listeners.stream().anyMatch(type::isInstance);
	}

	@Test
	public void enabledModuleResolvesTheZoneParkingCarCostModelAndKeepsEveryEstimator() throws Exception {
		Injector injector = injector(true);

		assertTrue(costModels(injector).get(BraunschweigModeChoiceModule.ZONE_PARKING_CAR_COST_MODEL_NAME)
				.get() instanceof ZoneParkingCarCostModel);
		CostModel carCostModel = carCostModel(injector);
		assertTrue(carCostModel instanceof ZoneParkingCarCostModel);
		// The tariff model is read through the config-relative path.
		assertEquals(18, injector.getInstance(ParkingTariffs.class).zones().size());
		assertTrue(hasListener(injector, ParkingOutcomeReportListener.class));
		// The startup check of the plans, which takes the tariff model when the controller builds its listeners.
		assertTrue(hasListener(injector, ParkingPopulationCheck.class));
		// The startup check of lever 4 (search time factor against the tariff model), built the same way.
		assertTrue(hasListener(injector, ParkingSearchTimeCheck.class));

		// Only the car cost model name changed: car and car passenger keep their estimators.
		EqasimConfigGroup eqasim = EqasimConfigGroup.get(injector.getInstance(Config.class));
		assertEquals(BraunschweigModeChoiceModule.CAR_ESTIMATOR_NAME, eqasim.getEstimators().get(TransportMode.car));
		assertEquals(BraunschweigModeChoiceModule.CAR_PASSENGER_ESTIMATOR_NAME,
				eqasim.getEstimators().get(BraunschweigModeChoiceModule.CAR_PASSENGER));
		assertEquals(BraunschweigModeChoiceModule.PT_COST_MODEL_NAME, eqasim.getCostModels().get(TransportMode.pt));
		Map<String, Provider<UtilityEstimator>> estimators = estimators(injector);
		assertTrue(estimators.get(BraunschweigModeChoiceModule.CAR_ESTIMATOR_NAME)
				.get() instanceof BraunschweigCarUtilityEstimator);
		assertTrue(estimators.get(BraunschweigModeChoiceModule.CAR_PASSENGER_ESTIMATOR_NAME)
				.get() instanceof BraunschweigCarPassengerUtilityEstimator);

		// The search time of lever 4 is bound with the tariff model and the factor of the module: 0 by default (off).
		ParkingSearchTime searchTime = injector.getInstance(ParkingSearchTime.class);
		assertNotSame(ParkingSearchTime.none(), searchTime);
		assertEquals(0.0, searchTime.factor(), 0.0);
		Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId("driver"));
		Activity zoned = PopulationUtils.createActivityFromCoord("shop", new Coord(0, 0));
		zoned.getAttributes().putAttribute(ZoneParkingCarCostModel.ZONE_ATTRIBUTE, "fx_bs_ia_v2");
		assertEquals(5.0, searchTime.minutes(person, zoned), 0.0);

		// The cost model records into the counter the report listener reads.
		Activity origin = PopulationUtils.createActivityFromCoord("home", new Coord(0, 0));
		Activity destination = PopulationUtils.createActivityFromCoord("work", new Coord(10000, 0));
		DiscreteModeChoiceTrip trip = new DiscreteModeChoiceTrip(origin, destination, TransportMode.car, List.of(), 0, 0, 0,
				new AttributesImpl());
		Leg leg = PopulationUtils.createLeg(TransportMode.car);
		Route route = RouteUtils.createGenericRouteImpl(Id.createLinkId("from"), Id.createLinkId("to"));
		route.setDistance(10000.0);
		leg.setRoute(route);
		ParkingOutcomeCounter counter = injector.getInstance(ParkingOutcomeCounter.class);
		counter.snapshotAndReset();
		carCostModel.calculateCost_MU(person, trip, List.of(leg));
		assertEquals(Map.of(new ParkingOutcomeCounter.Cell(ParkingOutcome.NO_ZONE, "", "work"), 1L),
				counter.snapshotAndReset());
	}

	/**
	 * Without the module the legacy car cost model stays, no zone cost model, listener or tariff model exists, and the only
	 * parking binding is the neutral search time that the car utility estimator needs.
	 */
	@Test
	public void withoutTheModuleTheLegacyCarCostModelStaysAndOnlyTheNeutralSearchTimeIsBound() throws Exception {
		Injector injector = injector(false);

		assertTrue(carCostModel(injector) instanceof BraunschweigCarCostModel);
		assertFalse(costModels(injector).containsKey(BraunschweigModeChoiceModule.ZONE_PARKING_CAR_COST_MODEL_NAME));
		assertFalse(hasListener(injector, ParkingOutcomeReportListener.class));
		// Nothing scans the population and nothing logs its parking coverage.
		assertFalse(hasListener(injector, ParkingPopulationCheck.class));
		assertFalse(hasListener(injector, ParkingSearchTimeCheck.class));
		assertNull(injector.getExistingBinding(Key.get(ParkingTariffs.class)));
		// Lever 4 without the module: the car utility estimator is built with the neutral search time (factor 0, no
		// minutes), although no tariff model exists to read search minutes from.
		assertSame(ParkingSearchTime.none(), injector.getInstance(ParkingSearchTime.class));
		assertTrue(estimators(injector).get(BraunschweigModeChoiceModule.CAR_ESTIMATOR_NAME)
				.get() instanceof BraunschweigCarUtilityEstimator);
	}

	/** The searchTimeFactor of the module in the prepared config is the factor of the bound search time (lever 4). */
	@Test
	public void theSearchTimeFactorOfTheModuleReachesTheBoundSearchTime() throws Exception {
		Injector injector = injector(Map.of("searchTimeFactor", "1.5"));
		ParkingSearchTime searchTime = injector.getInstance(ParkingSearchTime.class);
		assertEquals(1.5, searchTime.factor(), 0.0);
		assertTrue(estimators(injector).get(BraunschweigModeChoiceModule.CAR_ESTIMATOR_NAME)
				.get() instanceof BraunschweigCarUtilityEstimator);
		assertEquals(1.5, injector.getInstance(ParkingConfigGroup.class).searchTimeFactor(), 0.0);
	}

	/**
	 * The tariff model loads when the controller starts, not at the first car cost estimate, which comes only with the
	 * replanning of iteration 1 (never, with lastIteration = 0 as here): a broken file stops Controler.run() before
	 * iteration 0 with the reader's message. Two bindings need the model at the start: eqasim's routing policies
	 * instantiate every cost model when the injector is created (TripRouterModule, the car travel disutility, the
	 * PolicyModule and its DiscountPolicyExtension, which takes all bound cost models), and ParkingPopulationCheck, a
	 * controller listener that Controler.run() builds before the startup event, takes the model itself, so the start
	 * does not depend on that chain.
	 */
	@Test
	public void anInvalidTariffModelFailsWhenTheControllerStarts() throws Exception {
		Controler controller = controller(true, tariffModel -> tariffModel.put("schema_version", 4));

		RuntimeException error = assertThrows(RuntimeException.class, controller::run);
		IllegalArgumentException readerError = null;
		for (Throwable cause = error; cause != null && readerError == null; cause = cause.getCause()) {
			if (cause instanceof IllegalArgumentException illegalArgument) {
				readerError = illegalArgument;
			}
		}
		assertNotNull("no IllegalArgumentException of the tariff reader in " + error, readerError);
		assertTrue(readerError.getMessage(), readerError.getMessage().contains(TARIFFS_FILE));
		assertTrue(readerError.getMessage(), readerError.getMessage().contains("schema_version must be one of [1, 2, 3], got 4"));
		// No iteration has begun.
		assertFalse(Files.exists(folder.getRoot().toPath().resolve("output").resolve("ITERS").resolve("it.0")));
	}

	/** The IllegalStateException of the first startup check in the causes of the failure of a controller run. */
	private static IllegalStateException startupCheckError(RuntimeException error) {
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			if (cause instanceof IllegalStateException illegalState) {
				return illegalState;
			}
		}
		throw new AssertionError("no IllegalStateException of a startup check in " + error);
	}

	/**
	 * Lever 4 configured (searchTimeFactor 1.0) next to a global car parking search penalty from the command line
	 * (--mode-choice-parameter:car.constantParkingSearchPenalty_min) counts the search time twice: the run stops when the
	 * controller starts, before iteration 0. The check reads the effective mode parameters, the ones the car predictor reads
	 * after the overrides, so the default penalty 0 would not fail and the value in the message is the overridden one.
	 */
	@Test
	public void aPenaltyFromTheCommandLineWithASearchTimeFactorFailsWhenTheControllerStarts() throws Exception {
		Controler controller = controller(true, tariffModel -> {
		}, Map.of("searchTimeFactor", "1.0"),
				new String[] { "--mode-choice-parameter:car.constantParkingSearchPenalty_min", "3.0" }, config -> {
				});

		IllegalStateException checkError = startupCheckError(assertThrows(RuntimeException.class, controller::run));
		for (String fragment : new String[] { "car.constantParkingSearchPenalty_min is 3.0",
				"braunschweigParking.searchTimeFactor is 1.0", "counted twice" }) {
			assertTrue("'" + fragment + "' missing in: " + checkError.getMessage(), checkError.getMessage().contains(fragment));
		}
		// No iteration has begun.
		assertFalse(Files.exists(folder.getRoot().toPath().resolve("output").resolve("ITERS").resolve("it.0")));
	}

	/** The same stop for a penalty set in the mode parameters file (modeParametersPath of the eqasim config group). */
	@Test
	public void aPenaltyFromTheModeParametersFileWithASearchTimeFactorFailsWhenTheControllerStarts() throws Exception {
		Path modeParameters = folder.newFile("mode_parameters_with_penalty.yml").toPath();
		Files.writeString(modeParameters, "car.constantParkingSearchPenalty_min: 2.5\n");
		Controler controller = controller(true, tariffModel -> {
		}, Map.of("searchTimeFactor", "1.0"), new String[0],
				config -> EqasimConfigGroup.get(config).setModeParametersPath(modeParameters.toString()));

		IllegalStateException checkError = startupCheckError(assertThrows(RuntimeException.class, controller::run));
		for (String fragment : new String[] { "car.constantParkingSearchPenalty_min is 2.5", "counted twice" }) {
			assertTrue("'" + fragment + "' missing in: " + checkError.getMessage(), checkError.getMessage().contains(fragment));
		}
		assertFalse(Files.exists(folder.getRoot().toPath().resolve("output").resolve("ITERS").resolve("it.0")));
	}

	/**
	 * Lever 4 configured (searchTimeFactor 1.0) while no zone of the tariff model has a search time cannot act: the run
	 * stops when the controller starts, before iteration 0, with a message that names the factor, the tariffs path and
	 * the missing column values, instead of silently running as the baseline. Without the factor the same model starts.
	 */
	@Test
	public void aSearchTimeFactorWithoutAnyZoneSearchTimeFailsWhenTheControllerStarts() throws Exception {
		Controler controller = controller(true, tariffModel -> {
			for (JsonNode zone : tariffModel.get("zones")) {
				((ObjectNode) zone).putNull("search_time_min");
			}
		}, Map.of("searchTimeFactor", "1.0"));

		RuntimeException error = assertThrows(RuntimeException.class, controller::run);
		IllegalStateException checkError = null;
		for (Throwable cause = error; cause != null && checkError == null; cause = cause.getCause()) {
			if (cause instanceof IllegalStateException illegalState) {
				checkError = illegalState;
			}
		}
		assertNotNull("no IllegalStateException of the search time check in " + error, checkError);
		for (String fragment : new String[] { "braunschweigParking.searchTimeFactor", "1.0", TARIFFS_FILE,
				"no zone of the parking tariff model", "cannot act" }) {
			assertTrue("'" + fragment + "' missing in: " + checkError.getMessage(), checkError.getMessage().contains(fragment));
		}
		// No iteration has begun.
		assertFalse(Files.exists(folder.getRoot().toPath().resolve("output").resolve("ITERS").resolve("it.0")));
	}
}
