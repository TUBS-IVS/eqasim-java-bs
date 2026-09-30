package org.eqasim.braunschweig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

	/**
	 * The controller of a prepared scenario (empty population and network) whose tariff model is the fixture changed by
	 * {@code tariffModelChange}; nothing is injected yet.
	 */
	private Controler controller(boolean parkingEnabled, Consumer<ObjectNode> tariffModelChange) throws Exception {
		// The preparation stage writes the tariff model next to the prepared config; its path is config-relative.
		Path prepared = folder.newFolder("prepared").toPath();
		ObjectMapper mapper = new ObjectMapper();
		ObjectNode tariffModel = (ObjectNode) mapper
				.readTree(Path.of(getClass().getResource("/parking/parking_tariffs_fixture.json").toURI()).toFile());
		tariffModelChange.accept(tariffModel);
		mapper.writeValue(prepared.resolve(TARIFFS_FILE).toFile(), tariffModel);

		// The pipeline's config chain: RunGenerateConfig, RunAdaptConfig, the braunschweigParking module written by the
		// Python prepare stage, then RunSimulation's updateConfig on the loaded file.
		CommandLine cmd = new CommandLine.Builder(new String[0]).allowAnyOption(true).build();
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
			config.addModule(raw);
		}
		config.controller().setOutputDirectory(folder.newFolder("output").toString());
		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setLastIteration(0);
		configurator.updateConfig(config);

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
		assertEquals(8, injector.getInstance(ParkingTariffs.class).zones().size());
		assertTrue(hasListener(injector, ParkingOutcomeReportListener.class));
		// The startup check of the plans, which takes the tariff model when the controller builds its listeners.
		assertTrue(hasListener(injector, ParkingPopulationCheck.class));

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

		// The cost model records into the counter the report listener reads.
		Activity origin = PopulationUtils.createActivityFromCoord("home", new Coord(0, 0));
		Activity destination = PopulationUtils.createActivityFromCoord("work", new Coord(10000, 0));
		DiscreteModeChoiceTrip trip = new DiscreteModeChoiceTrip(origin, destination, TransportMode.car, List.of(), 0, 0, 0,
				new AttributesImpl());
		Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId("driver"));
		Leg leg = PopulationUtils.createLeg(TransportMode.car);
		Route route = RouteUtils.createGenericRouteImpl(Id.createLinkId("from"), Id.createLinkId("to"));
		route.setDistance(10000.0);
		leg.setRoute(route);
		ParkingOutcomeCounter counter = injector.getInstance(ParkingOutcomeCounter.class);
		counter.snapshotAndReset();
		carCostModel.calculateCost_MU(person, trip, List.of(leg));
		assertEquals(Long.valueOf(1), counter.snapshotAndReset().get(ParkingOutcome.NO_ZONE));
	}

	@Test
	public void withoutTheModuleTheLegacyCarCostModelStaysAndNothingOfParkingIsBound() throws Exception {
		Injector injector = injector(false);

		assertTrue(carCostModel(injector) instanceof BraunschweigCarCostModel);
		assertFalse(costModels(injector).containsKey(BraunschweigModeChoiceModule.ZONE_PARKING_CAR_COST_MODEL_NAME));
		assertFalse(hasListener(injector, ParkingOutcomeReportListener.class));
		// Nothing scans the population and nothing logs its parking coverage.
		assertFalse(hasListener(injector, ParkingPopulationCheck.class));
		assertNull(injector.getExistingBinding(Key.get(ParkingTariffs.class)));
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
		Controler controller = controller(true, tariffModel -> tariffModel.put("schema_version", 2));

		RuntimeException error = assertThrows(RuntimeException.class, controller::run);
		IllegalArgumentException readerError = null;
		for (Throwable cause = error; cause != null && readerError == null; cause = cause.getCause()) {
			if (cause instanceof IllegalArgumentException illegalArgument) {
				readerError = illegalArgument;
			}
		}
		assertNotNull("no IllegalArgumentException of the tariff reader in " + error, readerError);
		assertTrue(readerError.getMessage(), readerError.getMessage().contains(TARIFFS_FILE));
		assertTrue(readerError.getMessage(), readerError.getMessage().contains("schema_version must be 1, got 2"));
		// No iteration has begun.
		assertFalse(Files.exists(folder.getRoot().toPath().resolve("output").resolve("ITERS").resolve("it.0")));
	}
}
