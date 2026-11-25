package org.eqasim.core.standalone_mode_choice;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contribs.discrete_mode_choice.components.tour_finder.TourFinder;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceModel;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourFilter;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TripFilter;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.OutputDirectoryLogging;
import org.matsim.core.utils.io.IOUtils;

import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.Singleton;

public class StandaloneModeChoiceModule extends AbstractModule {
    @Override
    public void install() {
        StandaloneModeChoiceConfigGroup configGroup = (StandaloneModeChoiceConfigGroup) getConfig().getModules()
                .get(StandaloneModeChoiceConfigGroup.GROUP_NAME);

        if (configGroup == null) {
            throw new IllegalStateException(
                    String.format("%s module is required in the config", StandaloneModeChoiceConfigGroup.GROUP_NAME));
        }

        DiscreteModeChoiceConfigGroup discreteModeChoiceConfigGroup = (DiscreteModeChoiceConfigGroup) getConfig()
                .getModules().get(DiscreteModeChoiceConfigGroup.GROUP_NAME);

        if (discreteModeChoiceConfigGroup == null) {
            throw new IllegalStateException(
                    String.format("%s module is required in the config", DiscreteModeChoiceConfigGroup.GROUP_NAME));
        } else if (!discreteModeChoiceConfigGroup.getFallbackBehaviour()
                .equals(DiscreteModeChoiceModel.FallbackBehaviour.EXCEPTION)
                && configGroup.isRemovePersonsWithNoValidAlternative()) {
            throw new IllegalStateException(String.format(
                    "The %s module relies on the exceptions thrown by the %s module to filter out persons with no alternatives. "
                            +
                            "The %s attribute of the latter needs to be set to %s when the %s attribute of the former is set to true",
                    StandaloneModeChoiceConfigGroup.GROUP_NAME, DiscreteModeChoiceConfigGroup.GROUP_NAME,
                    DiscreteModeChoiceConfigGroup.FALLBACK_BEHAVIOUR,
                    DiscreteModeChoiceModel.FallbackBehaviour.EXCEPTION,
                    StandaloneModeChoiceConfigGroup.REMOVE_PERSON_WITH_NO_VALID_ALTERNATIVES));
        }
    }

    @Provides
    public StandaloneModeChoicePerformer provideStandaloneModeChoicePerformer(
            Provider<DiscreteModeChoiceModel> discreteModeChoiceModelProvider,
            Provider<FilteredTripFinder> filteredTripFinderProvider, Population population,
            OutputDirectoryHierarchy outputDirectoryHierarchy, Scenario scenario,
            StandaloneModeChoiceConfigGroup configGroup) {
        int numberOfThreads = getConfig().global().getNumberOfThreads();
        long randomSeed = getConfig().global().getRandomSeed();

        return new StandaloneModeChoicePerformer(discreteModeChoiceModelProvider, filteredTripFinderProvider,
                configGroup, population, numberOfThreads, randomSeed, outputDirectoryHierarchy, scenario);
    }

    /**
     * The trips the configured model does not re-choose: those of tours (tour-based models) or the trips (trip-based
     * model) its filter rejects. Uses the model's own tour finder and filter (eqasim-bs#678).
     */
    @Provides
    public FilteredTripFinder provideFilteredTripFinder(DiscreteModeChoiceConfigGroup dmcConfig,
            Provider<TourFinder> tourFinder, Provider<TourFilter> tourFilter, Provider<TripFilter> tripFilter) {
        switch (dmcConfig.getModelType()) {
        case Tour:
        case EfficientTour:
            return FilteredTripFinder.forTours(tourFinder.get(), tourFilter.get());
        case Trip:
            return FilteredTripFinder.forTrips(tripFilter.get());
        default:
            throw new IllegalStateException("Unknown discrete mode choice model type " + dmcConfig.getModelType());
        }
    }

    @Provides
    @Singleton
    public OutputDirectoryHierarchy provideOutputDirectoryHierarchy(StandaloneModeChoiceConfigGroup configGroup) {
        OutputDirectoryHierarchy outputDirectoryHierarchy = new OutputDirectoryHierarchy(
                configGroup.getOutputDirectory(), null,
                OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles, false,
                ControllerConfigGroup.CompressionType.gzip);
        File outputDir = new File(outputDirectoryHierarchy.getOutputPath());
        if (outputDir.exists()) {
            if (outputDir.isFile()) {
                throw new RuntimeException("Cannot create output directory. "
                        + outputDirectoryHierarchy.getOutputPath()
                        + " is a file and cannot be replaced by a directory.");
            }
            if (Objects.requireNonNull(outputDir.list()).length > 0) {
                IOUtils.deleteDirectoryRecursively(outputDir.toPath());
            }
        }
        try {
            OutputDirectoryLogging.initLoggingWithOutputDirectory(configGroup.getOutputDirectory());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return outputDirectoryHierarchy;
    }
}
