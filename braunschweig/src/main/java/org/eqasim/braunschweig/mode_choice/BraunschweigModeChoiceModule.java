package org.eqasim.braunschweig.mode_choice;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.eqasim.core.components.config.EqasimConfigGroup;
import org.eqasim.core.simulation.mode_choice.AbstractEqasimExtension;
import org.eqasim.core.simulation.mode_choice.ParameterDefinition;
import org.eqasim.core.simulation.mode_choice.parameters.ModeParameters;
import org.eqasim.core.simulation.mode_choice.tour_finder.ActivityTourFinderWithExcludedActivities;
import org.eqasim.core.simulation.mode_choice.utilities.estimators.BikeUtilityEstimator;
import org.eqasim.braunschweig.mode_choice.costs.BraunschweigCarCostModel;
import org.eqasim.braunschweig.mode_choice.costs.BraunschweigPtCostModel;
import org.eqasim.braunschweig.mode_choice.parameters.BraunschweigCostParameters;
import org.eqasim.braunschweig.mode_choice.parameters.BraunschweigModeParameters;
import org.eqasim.braunschweig.mode_choice.utilities.estimators.BraunschweigBicycleUtilityEstimator;
import org.eqasim.braunschweig.mode_choice.utilities.estimators.BraunschweigCarPassengerUtilityEstimator;
import org.eqasim.braunschweig.mode_choice.utilities.estimators.BraunschweigCarUtilityEstimator;
import org.eqasim.braunschweig.mode_choice.utilities.estimators.BraunschweigPtUtilityEstimator;
import org.eqasim.braunschweig.mode_choice.utilities.predictors.BraunschweigCarPassengerPredictor;
import org.eqasim.braunschweig.mode_choice.utilities.predictors.BraunschweigPersonPredictor;
import org.eqasim.braunschweig.mode_choice.utilities.predictors.BraunschweigPtPredictor;
import org.matsim.contribs.discrete_mode_choice.components.tour_finder.ActivityTourFinder;
import org.matsim.contribs.discrete_mode_choice.modules.config.ActivityTourFinderConfigGroup;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;

import com.google.inject.Provides;
import com.google.inject.Singleton;

public class BraunschweigModeChoiceModule extends AbstractEqasimExtension {
	private final CommandLine commandLine;

	public static final String MODE_AVAILABILITY_NAME = "BraunschweigModeAvailability";

	public static final String CAR_COST_MODEL_NAME = "BraunschweigCarCostModel";
	public static final String PT_COST_MODEL_NAME = "MunichPtCostModel";

	public static final String CAR_ESTIMATOR_NAME = "BraunschweigCarUtilityEstimator";
	public static final String CAR_PASSENGER_ESTIMATOR_NAME = "BraunschweigCarPassengerUtilityEstimator";
	public static final String BICYCLE_ESTIMATOR_NAME = "BraunschweigBicycleUtilityEstimator";
	public static final String PT_ESTIMATOR_NAME = "BraunschweigPtUtilityEstimator";

	static public final String CAR_PASSENGER = "car_passenger";
	static public final String BICYCLE = "bicycle";

	public static final String ISOLATED_OUTSIDE_TOUR_FINDER_NAME = "IsolatedOutsideTrips";

	public BraunschweigModeChoiceModule(CommandLine commandLine) {
		this.commandLine = commandLine;
	}

	@Override
	protected void installEqasimExtension() {
		bindModeAvailability(MODE_AVAILABILITY_NAME).to(BraunschweigModeAvailability.class);

		bind(BraunschweigPersonPredictor.class);
		bind(BraunschweigCarPassengerPredictor.class);
		bind(BraunschweigPtPredictor.class);

		bindCostModel(CAR_COST_MODEL_NAME).to(BraunschweigCarCostModel.class);
		bindCostModel(PT_COST_MODEL_NAME).to(BraunschweigPtCostModel.class);

		bindUtilityEstimator(CAR_ESTIMATOR_NAME).to(BraunschweigCarUtilityEstimator.class);
		bindUtilityEstimator(BICYCLE_ESTIMATOR_NAME).to(BraunschweigBicycleUtilityEstimator.class);
		bindUtilityEstimator(CAR_PASSENGER_ESTIMATOR_NAME).to(BraunschweigCarPassengerUtilityEstimator.class);
		bindUtilityEstimator(PT_ESTIMATOR_NAME).to(BraunschweigPtUtilityEstimator.class);

		bind(ModeParameters.class).to(BraunschweigModeParameters.class);

		bindTourFinder(ISOLATED_OUTSIDE_TOUR_FINDER_NAME).to(ActivityTourFinderWithExcludedActivities.class);
	}

	@Provides
	@Singleton
	public BraunschweigModeParameters provideModeChoiceParameters(EqasimConfigGroup config)
			throws IOException, ConfigurationException {
		BraunschweigModeParameters parameters = BraunschweigModeParameters.buildDefault();

		if (config.getModeParametersPath() != null) {
			ParameterDefinition.applyFile(new File(config.getModeParametersPath()), parameters);
		}

		ParameterDefinition.applyCommandLine("mode-choice-parameter", commandLine, parameters);
		return parameters;
	}

	@Provides
	@Singleton
	public BraunschweigCostParameters provideCostParameters(EqasimConfigGroup config) {
		BraunschweigCostParameters parameters = BraunschweigCostParameters.buildDefault();

		if (config.getCostParametersPath() != null) {
			ParameterDefinition.applyFile(new File(config.getCostParametersPath()), parameters);
		}

		ParameterDefinition.applyCommandLine("cost-parameter", commandLine, parameters);
		return parameters;
	}

	@Provides
	@Singleton
	public ActivityTourFinderWithExcludedActivities provideActivityTourFinderWithExcludedActivities(
			DiscreteModeChoiceConfigGroup dmcConfig) {
		ActivityTourFinderConfigGroup config = dmcConfig.getActivityTourFinderConfigGroup();
		return new ActivityTourFinderWithExcludedActivities(List.of("outside"),
				new ActivityTourFinder(config.getActivityTypes()));
	}
}
