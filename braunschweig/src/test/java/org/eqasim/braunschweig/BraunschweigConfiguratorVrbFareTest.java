package org.eqasim.braunschweig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.eqasim.braunschweig.fares.zonal.VrbFareConfigGroup;
import org.eqasim.braunschweig.mode_choice.BraunschweigModeChoiceModule;
import org.eqasim.core.components.config.EqasimConfigGroup;
import org.junit.Test;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;

public class BraunschweigConfiguratorVrbFareTest {
	private static Config dmcConfig() {
		Config config = ConfigUtils.createConfig(new DiscreteModeChoiceConfigGroup());
		DiscreteModeChoiceConfigGroup dmc = (DiscreteModeChoiceConfigGroup) config.getModules()
				.get(DiscreteModeChoiceConfigGroup.GROUP_NAME);
		dmc.setCachedModes(List.of("car", "pt", "walk"));
		return config;
	}

	private static BraunschweigConfigurator configurator() throws Exception {
		return new BraunschweigConfigurator(new CommandLine.Builder(new String[0]).allowAnyOption(true).build());
	}

	@Test
	public void offKeepsCachedModesTourEstimatorAndLegacyPtNames() throws Exception {
		Config config = dmcConfig();
		DiscreteModeChoiceConfigGroup dmc = (DiscreteModeChoiceConfigGroup) config.getModules()
				.get(DiscreteModeChoiceConfigGroup.GROUP_NAME);
		String originalTourEstimator = dmc.getTourEstimator();
		configurator().updateConfig(config);
		assertEquals(Set.of("car", "pt", "walk"), Set.copyOf(dmc.getCachedModes()));
		assertEquals(originalTourEstimator, dmc.getTourEstimator());
		assertFalse(config.getModules().containsKey(VrbFareConfigGroup.GROUP_NAME));
	}

	@Test
	public void onWithCapSwitchesPtNamesRemovesPtFromCacheAndSetsPrefixAwareTourEstimator() throws Exception {
		Config config = dmcConfig();
		ConfigGroup raw = new ConfigGroup(VrbFareConfigGroup.GROUP_NAME);
		raw.addParam("enabled", "true");
		raw.addParam("fareModelPath", "vrb_fare_model_2026.json");
		raw.addParam("lineScopesPath", "vrb_line_scopes.csv");
		config.addModule(raw);
		configurator().updateConfig(config);
		DiscreteModeChoiceConfigGroup dmc = (DiscreteModeChoiceConfigGroup) config.getModules()
				.get(DiscreteModeChoiceConfigGroup.GROUP_NAME);
		assertTrue(config.getModules().get(VrbFareConfigGroup.GROUP_NAME) instanceof VrbFareConfigGroup);
		assertEquals(Set.of("car", "walk"), Set.copyOf(dmc.getCachedModes()));
		assertEquals(BraunschweigModeChoiceModule.PREFIX_AWARE_TOUR_ESTIMATOR_NAME, dmc.getTourEstimator());
		EqasimConfigGroup eqasim = EqasimConfigGroup.get(config);
		assertEquals(BraunschweigModeChoiceModule.VRB_FARE_PT_COST_MODEL_NAME, eqasim.getCostModels().get(TransportMode.pt));
		assertEquals(BraunschweigModeChoiceModule.VRB_FARE_PT_ESTIMATOR_NAME, eqasim.getEstimators().get(TransportMode.pt));
	}
}
