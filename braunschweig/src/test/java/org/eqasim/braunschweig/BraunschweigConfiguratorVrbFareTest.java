package org.eqasim.braunschweig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.eqasim.braunschweig.fares.zonal.VrbFareConfigGroup;
import org.eqasim.braunschweig.mode_choice.BraunschweigModeChoiceModule;
import org.eqasim.core.components.config.EqasimConfigGroup;
import org.junit.Test;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contribs.discrete_mode_choice.modules.EstimatorModule;
import org.matsim.contribs.discrete_mode_choice.modules.ModelModule.ModelType;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;

public class BraunschweigConfiguratorVrbFareTest {
	private static Config dmcConfig() {
		// Legacy pt names as RunAdaptConfig writes them into the prepared config.
		EqasimConfigGroup eqasim = new EqasimConfigGroup();
		eqasim.setCostModel(TransportMode.pt, BraunschweigModeChoiceModule.PT_COST_MODEL_NAME);
		eqasim.setEstimator(TransportMode.pt, BraunschweigModeChoiceModule.PT_ESTIMATOR_NAME);
		Config config = ConfigUtils.createConfig(new DiscreteModeChoiceConfigGroup(), eqasim);
		DiscreteModeChoiceConfigGroup dmc = dmc(config);
		dmc.setModelType(ModelType.Tour);
		dmc.setTourEstimator(EstimatorModule.CUMULATIVE);
		dmc.setCachedModes(List.of("car", "pt", "walk"));
		return config;
	}

	private static DiscreteModeChoiceConfigGroup dmc(Config config) {
		return (DiscreteModeChoiceConfigGroup) config.getModules().get(DiscreteModeChoiceConfigGroup.GROUP_NAME);
	}

	private static void addVrbFare(Config config, boolean dayTicketCap) {
		ConfigGroup raw = new ConfigGroup(VrbFareConfigGroup.GROUP_NAME);
		raw.addParam("enabled", "true");
		raw.addParam("fareModelPath", "vrb_fare_model_2026.json");
		raw.addParam("lineScopesPath", "vrb_line_scopes.csv");
		raw.addParam("dayTicketCapEnabled", Boolean.toString(dayTicketCap));
		config.addModule(raw);
	}

	private static BraunschweigConfigurator configurator() throws Exception {
		return new BraunschweigConfigurator(new CommandLine.Builder(new String[0]).allowAnyOption(true).build());
	}

	@Test
	public void offKeepsCachedModesTourEstimatorAndLegacyPtNames() throws Exception {
		Config config = dmcConfig();
		configurator().updateConfig(config);
		assertEquals(Set.of("car", "pt", "walk"), Set.copyOf(dmc(config).getCachedModes()));
		assertEquals(EstimatorModule.CUMULATIVE, dmc(config).getTourEstimator());
		assertFalse(config.getModules().containsKey(VrbFareConfigGroup.GROUP_NAME));
		EqasimConfigGroup eqasim = EqasimConfigGroup.get(config);
		assertEquals(BraunschweigModeChoiceModule.PT_COST_MODEL_NAME, eqasim.getCostModels().get(TransportMode.pt));
		assertEquals(BraunschweigModeChoiceModule.PT_ESTIMATOR_NAME, eqasim.getEstimators().get(TransportMode.pt));
	}

	@Test
	public void onWithCapSwitchesPtNamesKeepsPtCachedAndSetsTheDayTicketCapTourEstimator() throws Exception {
		Config config = dmcConfig();
		addVrbFare(config, true);
		configurator().updateConfig(config);
		assertTrue(config.getModules().get(VrbFareConfigGroup.GROUP_NAME) instanceof VrbFareConfigGroup);
		// The cap is applied as a utility correction in the tour estimator, so pt keeps its estimate cache.
		assertEquals(Set.of("car", "pt", "walk"), Set.copyOf(dmc(config).getCachedModes()));
		assertEquals(BraunschweigModeChoiceModule.DAY_TICKET_CAP_TOUR_ESTIMATOR_NAME, dmc(config).getTourEstimator());
		EqasimConfigGroup eqasim = EqasimConfigGroup.get(config);
		assertEquals(BraunschweigModeChoiceModule.VRB_FARE_PT_COST_MODEL_NAME, eqasim.getCostModels().get(TransportMode.pt));
		assertEquals(BraunschweigModeChoiceModule.VRB_FARE_PT_ESTIMATOR_NAME, eqasim.getEstimators().get(TransportMode.pt));
	}

	@Test
	public void onWithoutCapKeepsTheCumulativeTourEstimator() throws Exception {
		Config config = dmcConfig();
		addVrbFare(config, false);
		configurator().updateConfig(config);
		assertEquals(EstimatorModule.CUMULATIVE, dmc(config).getTourEstimator());
		assertEquals(BraunschweigModeChoiceModule.VRB_FARE_PT_COST_MODEL_NAME,
				EqasimConfigGroup.get(config).getCostModels().get(TransportMode.pt));
	}

	@Test
	public void capFailsLoudlyWhenModeChoiceIsNotTourBasedOrUsesAnotherTourEstimator() throws Exception {
		// A trip-based model never calls a tour estimator, so the cap would silently not apply.
		Config tripBased = dmcConfig();
		dmc(tripBased).setModelType(ModelType.Trip);
		addVrbFare(tripBased, true);
		assertThrows(IllegalStateException.class, () -> configurator().updateConfig(tripBased));
		// The cap estimator reproduces the cumulative one; replacing a different estimator would change more than the fare.
		Config otherEstimator = dmcConfig();
		dmc(otherEstimator).setTourEstimator("SomeOtherTourEstimator");
		addVrbFare(otherEstimator, true);
		assertThrows(IllegalStateException.class, () -> configurator().updateConfig(otherEstimator));
	}
}
