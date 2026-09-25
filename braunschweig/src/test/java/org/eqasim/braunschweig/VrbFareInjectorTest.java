package org.eqasim.braunschweig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eqasim.braunschweig.fares.zonal.DayTicketCapAdjustment;
import org.eqasim.braunschweig.fares.zonal.DayTicketCapTourEstimator;
import org.eqasim.braunschweig.fares.zonal.FareQuoteSource;
import org.eqasim.braunschweig.fares.zonal.LongDistanceFareRaptorCostCalculator;
import org.eqasim.braunschweig.fares.zonal.LongDistanceSurchargeStopFinder;
import org.eqasim.braunschweig.fares.zonal.VrbFareConfigGroup;
import org.eqasim.braunschweig.fares.zonal.VrbZoneFareCostModel;
import org.eqasim.braunschweig.mode_choice.BraunschweigModeChoiceModule;
import org.eqasim.braunschweig.mode_choice.utilities.estimators.VrbZoneFarePtUtilityEstimator;
import org.eqasim.braunschweig.scenario.RunAdaptConfig;
import org.eqasim.core.scenario.config.GenerateConfig;
import org.eqasim.core.simulation.mode_choice.cost.CostModel;
import org.eqasim.core.simulation.mode_choice.utilities.UtilityEstimator;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourEstimator;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.scenario.ScenarioUtils;

import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorInVehicleCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorStopFinder;
import ch.sbb.matsim.routing.pt.raptor.RaptorInVehicleCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.RaptorStopFinder;

import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.TypeLiteral;

/**
 * Builds the real MATSim controller injector with an enabled vrbFare module, the way RunSimulation
 * does, and resolves every VRB binding mode choice uses. Guards the controller start of an ON run,
 * which no unit test of a single class can see (e.g. a binding that collides with MATSim's own
 * config-group bindings).
 */
public class VrbFareInjectorTest {
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private Injector onInjector() throws Exception {
		return onInjector(true);
	}

	private Injector onInjector(boolean routingSurcharge) throws Exception {
		Path fareModel = Path.of(getClass().getResource("/vrb-zone-fares/vrb_fare_model_2026.json").toURI());
		Path lineScopes = folder.newFile("vrb_line_scopes.csv").toPath();
		Files.writeString(lineScopes, "line_id,agency_id,agency_name,route_type,mode_class,tariff_scope\n");

		// The pipeline's config chain: RunGenerateConfig, RunAdaptConfig, the vrbFare module written by
		// the Python prepare stage, then RunSimulation's updateConfig on the loaded file.
		CommandLine cmd = new CommandLine.Builder(new String[0]).allowAnyOption(true).build();
		BraunschweigConfigurator configurator = new BraunschweigConfigurator(cmd);
		Config config = ConfigUtils.createConfig();
		configurator.updateConfig(config);
		new GenerateConfig(cmd, "", 0.01, 0, 1).run(config);
		RunAdaptConfig.adaptConfiguration(config, "");

		ConfigGroup raw = new ConfigGroup(VrbFareConfigGroup.GROUP_NAME);
		raw.addParam("enabled", "true");
		raw.addParam("fareModelPath", fareModel.toString());
		raw.addParam("lineScopesPath", lineScopes.toString());
		raw.addParam("longDistanceRoutingSurchargeEnabled", Boolean.toString(routingSurcharge));
		config.addModule(raw);
		config.controller().setOutputDirectory(folder.newFolder("output").toString());
		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setLastIteration(0);
		configurator.updateConfig(config);

		Scenario scenario = ScenarioUtils.createScenario(config);
		configurator.configureScenario(scenario);
		Controler controller = new Controler(scenario);
		configurator.configureController(controller);
		return controller.getInjector();
	}

	@Test
	public void onInjectorResolvesTheVrbCostModelEstimatorAndTourEstimator() throws Exception {
		Injector injector = onInjector();

		Map<String, Provider<CostModel>> costModels = injector
				.getInstance(Key.get(new TypeLiteral<Map<String, Provider<CostModel>>>() {
				}));
		CostModel costModel = costModels.get(BraunschweigModeChoiceModule.VRB_FARE_PT_COST_MODEL_NAME).get();
		assertTrue(costModel instanceof VrbZoneFareCostModel);
		assertSame(costModel, injector.getInstance(FareQuoteSource.class));

		Map<String, Provider<UtilityEstimator>> estimators = injector
				.getInstance(Key.get(new TypeLiteral<Map<String, Provider<UtilityEstimator>>>() {
				}));
		assertTrue(estimators.get(BraunschweigModeChoiceModule.VRB_FARE_PT_ESTIMATOR_NAME)
				.get() instanceof VrbZoneFarePtUtilityEstimator);

		DiscreteModeChoiceConfigGroup dmc = (DiscreteModeChoiceConfigGroup) injector.getInstance(Config.class)
				.getModules().get(DiscreteModeChoiceConfigGroup.GROUP_NAME);
		Map<String, Provider<TourEstimator>> tourEstimators = injector
				.getInstance(Key.get(new TypeLiteral<Map<String, Provider<TourEstimator>>>() {
				}));
		assertEquals(BraunschweigModeChoiceModule.DAY_TICKET_CAP_TOUR_ESTIMATOR_NAME, dmc.getTourEstimator());
		assertTrue(tourEstimators.get(dmc.getTourEstimator()).get() instanceof DayTicketCapTourEstimator);
		assertTrue(injector.getInstance(DayTicketCapAdjustment.class) instanceof VrbZoneFarePtUtilityEstimator);
		// The cap is a correction in the tour estimator, so the pt estimate cache of the generated config stays.
		assertTrue(dmc.getCachedModes().contains("pt"));
		// The router prices long-distance rides (ADR-0133 D6); this overrides SwissRailRaptorModule's default.
		assertTrue(injector.getInstance(RaptorInVehicleCostCalculator.class) instanceof LongDistanceFareRaptorCostCalculator);
		assertTrue(injector.getInstance(RaptorStopFinder.class) instanceof LongDistanceSurchargeStopFinder);
	}

	@Test
	public void switchedOffRoutingSurchargeKeepsTheDefaultRaptorInVehicleCost() throws Exception {
		Injector injector = onInjector(false);
		assertTrue(injector.getInstance(RaptorInVehicleCostCalculator.class) instanceof DefaultRaptorInVehicleCostCalculator);
		assertTrue(injector.getInstance(RaptorStopFinder.class) instanceof DefaultRaptorStopFinder);
	}
}
