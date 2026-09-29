package org.eqasim.braunschweig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.eqasim.braunschweig.fares.zonal.VrbFareConfigGroup;
import org.eqasim.braunschweig.mode_choice.BraunschweigModeChoiceModule;
import org.eqasim.braunschweig.parking.ParkingConfigGroup;
import org.eqasim.core.components.config.EqasimConfigGroup;
import org.junit.Test;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;

/**
 * Configuration switch of the zone-based parking costs (design "parking cost zones", section 3.6): an enabled
 * braunschweigParking module replaces the car cost model name and nothing else; an absent or disabled module leaves
 * the prepared config exactly as RunAdaptConfig wrote it.
 */
public class BraunschweigConfiguratorParkingTest {
	/** Legacy cost model and estimator names as RunAdaptConfig writes them into the prepared config. */
	private static Config preparedConfig() {
		EqasimConfigGroup eqasim = new EqasimConfigGroup();
		eqasim.setCostModel(TransportMode.car, BraunschweigModeChoiceModule.CAR_COST_MODEL_NAME);
		eqasim.setCostModel(TransportMode.pt, BraunschweigModeChoiceModule.PT_COST_MODEL_NAME);
		eqasim.setEstimator(TransportMode.car, BraunschweigModeChoiceModule.CAR_ESTIMATOR_NAME);
		eqasim.setEstimator(TransportMode.pt, BraunschweigModeChoiceModule.PT_ESTIMATOR_NAME);
		eqasim.setEstimator(BraunschweigModeChoiceModule.CAR_PASSENGER, BraunschweigModeChoiceModule.CAR_PASSENGER_ESTIMATOR_NAME);
		return ConfigUtils.createConfig(new DiscreteModeChoiceConfigGroup(), eqasim);
	}

	/** The module as the preparation stage writes it into the prepared config: a generic group of string parameters. */
	private static void addParking(Config config, String enabled, String tariffsPath) {
		ConfigGroup raw = new ConfigGroup(ParkingConfigGroup.GROUP_NAME);
		raw.addParam("enabled", enabled);
		if (tariffsPath != null) {
			raw.addParam("tariffsPath", tariffsPath);
		}
		config.addModule(raw);
	}

	private static void addVrbFare(Config config) {
		ConfigGroup raw = new ConfigGroup(VrbFareConfigGroup.GROUP_NAME);
		raw.addParam("enabled", "true");
		raw.addParam("fareModelPath", "vrb_fare_model_2026.json");
		raw.addParam("lineScopesPath", "vrb_line_scopes.csv");
		raw.addParam("dayTicketCapEnabled", "false");
		config.addModule(raw);
	}

	private static BraunschweigConfigurator configurator() throws Exception {
		return new BraunschweigConfigurator(new CommandLine.Builder(new String[0]).allowAnyOption(true).build());
	}

	private static Map<String, String> costModels(Config config) {
		return new HashMap<>(EqasimConfigGroup.get(config).getCostModels());
	}

	private static Map<String, String> estimators(Config config) {
		return new HashMap<>(EqasimConfigGroup.get(config).getEstimators());
	}

	@Test
	public void absentModuleKeepsTheLegacyCarCostModel() throws Exception {
		Config config = preparedConfig();
		Map<String, String> costModelsBefore = costModels(config);
		Map<String, String> estimatorsBefore = estimators(config);
		configurator().updateConfig(config);
		assertEquals(BraunschweigModeChoiceModule.CAR_COST_MODEL_NAME,
				EqasimConfigGroup.get(config).getCostModels().get(TransportMode.car));
		assertEquals(costModelsBefore, costModels(config));
		assertEquals(estimatorsBefore, estimators(config));
		// The group is optional: an absent module is not added.
		assertFalse(config.getModules().containsKey(ParkingConfigGroup.GROUP_NAME));
	}

	@Test
	public void disabledModuleKeepsTheLegacyCarCostModel() throws Exception {
		Config config = preparedConfig();
		addParking(config, "false", "parking_tariffs_2026-09-28.json");
		Map<String, String> costModelsBefore = costModels(config);
		configurator().updateConfig(config);
		assertTrue(config.getModules().get(ParkingConfigGroup.GROUP_NAME) instanceof ParkingConfigGroup);
		assertEquals(costModelsBefore, costModels(config));
	}

	@Test
	public void enabledModuleSwitchesTheCarCostModelNameOnly() throws Exception {
		Config config = preparedConfig();
		addParking(config, "true", "parking_tariffs_2026-09-28.json");
		Map<String, String> expectedCostModels = costModels(config);
		expectedCostModels.put(TransportMode.car, BraunschweigModeChoiceModule.ZONE_PARKING_CAR_COST_MODEL_NAME);
		Map<String, String> estimatorsBefore = estimators(config);
		configurator().updateConfig(config);
		assertTrue(config.getModules().get(ParkingConfigGroup.GROUP_NAME) instanceof ParkingConfigGroup);
		assertEquals("ZoneParkingCarCostModel", EqasimConfigGroup.get(config).getCostModels().get(TransportMode.car));
		// The pt names and every estimator, including car and car passenger, stay.
		assertEquals(expectedCostModels, costModels(config));
		assertEquals(estimatorsBefore, estimators(config));
	}

	@Test
	public void enabledModuleWithoutTariffsPathFails() throws Exception {
		Config config = preparedConfig();
		addParking(config, "true", null);
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> configurator().updateConfig(config));
		assertTrue(error.getMessage(), error.getMessage().contains("braunschweigParking.tariffsPath"));
	}

	/** Parking zones and VRB zone fares switch different modes and are independent of each other. */
	@Test
	public void parkingAndVrbFareSwitchTheirOwnModesTogether() throws Exception {
		Config config = preparedConfig();
		addParking(config, "true", "parking_tariffs_2026-09-28.json");
		addVrbFare(config);
		configurator().updateConfig(config);
		EqasimConfigGroup eqasim = EqasimConfigGroup.get(config);
		assertEquals(BraunschweigModeChoiceModule.ZONE_PARKING_CAR_COST_MODEL_NAME,
				eqasim.getCostModels().get(TransportMode.car));
		assertEquals(BraunschweigModeChoiceModule.VRB_FARE_PT_COST_MODEL_NAME, eqasim.getCostModels().get(TransportMode.pt));
		assertEquals(BraunschweigModeChoiceModule.VRB_FARE_PT_ESTIMATOR_NAME, eqasim.getEstimators().get(TransportMode.pt));
		assertEquals(BraunschweigModeChoiceModule.CAR_ESTIMATOR_NAME, eqasim.getEstimators().get(TransportMode.car));
	}
}
