package org.eqasim.core.standalone_mode_choice;


import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.core.misc.ParallelProgress;
import org.eqasim.core.simulation.mode_choice.filters.FilteredTripCounter;
import org.eqasim.core.scenario.routing.RunPopulationRouting;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdSet;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceModel;
import org.matsim.contribs.discrete_mode_choice.replanning.DiscreteModeChoiceAlgorithm;
import org.matsim.contribs.discrete_mode_choice.replanning.TripListConverter;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.misc.Counter;

import com.google.inject.Provider;

public class StandaloneModeChoicePerformer {

    private static final Logger logger = LogManager.getLogger(StandaloneModeChoicePerformer.class);

    private final Provider<DiscreteModeChoiceModel> discreteModeChoiceModelProvider;
    private final FilteredTripCounter filteredTripCounter;
    private final boolean removePersonsWithBadPlans;
    private final Population population;
    private final int numberOfThreads;
    private final long seed;
    private final int chunkSize;
    private final OutputDirectoryHierarchy outputDirectoryHierarchy;
    private final Scenario scenario;
    private final Set<String> excludedSubpopulations;

    /**
     * @param filteredTripCounter counts the trips the (injected, {@code KeepFilteredTripsModel}-wrapped) model keeps
     *                            unchanged because a filter rejects them (eqasim-bs#678); logged after the choice
     */
    public StandaloneModeChoicePerformer(Provider<DiscreteModeChoiceModel> discreteModeChoiceModelProvider, FilteredTripCounter filteredTripCounter, StandaloneModeChoiceConfigGroup configGroup, Population population, int numberOfThreads, long seed, OutputDirectoryHierarchy outputDirectoryHierarchy, Scenario scenario) {
        this.discreteModeChoiceModelProvider = discreteModeChoiceModelProvider;
        this.filteredTripCounter = filteredTripCounter;
        this.removePersonsWithBadPlans = configGroup.isRemovePersonsWithNoValidAlternative();
        this.numberOfThreads = numberOfThreads;
        this.population = population;
        this.seed = seed;
        this.outputDirectoryHierarchy = outputDirectoryHierarchy;
        this.scenario = scenario;
        this.chunkSize = configGroup.getChunkSize();
        this.excludedSubpopulations = configGroup.getExcludedSubpopulationSet();
    }

    /**
     * Logs how many persons each excluded subpopulation kept unchanged. Fails if the exclusion was asked for but
     * matched no person at all (for example because the subpopulation attribute was lost upstream, eqasim-bs #566):
     * the excluded persons would then be re-chosen silently. A single listed subpopulation without persons only warns,
     * because a small sample can legitimately lack one.
     */
    private void logExcludedSubpopulations(Map<String, Integer> excludedCounts) {
        if (excludedCounts.isEmpty()) {
            return;
        }
        int total = population.getPersons().size();
        int excluded = excludedCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (excluded == 0) {
            throw new IllegalStateException(String.format(
                    "%s.%s lists %s, but no person of the population (%d persons) has one of these subpopulations; "
                            + "the subpopulation attribute is missing or misspelled",
                    StandaloneModeChoiceConfigGroup.GROUP_NAME, StandaloneModeChoiceConfigGroup.EXCLUDED_SUBPOPULATIONS,
                    excludedCounts.keySet(), total));
        }
        logger.info(String.format("Excluded subpopulations: %d of %d persons (%.2f %%) keep their plan unchanged: %s",
                excluded, total, 100.0 * excluded / total, excludedCounts));
        excludedCounts.forEach((subpopulation, count) -> {
            if (count == 0) {
                logger.warn(String.format("Excluded subpopulation %s matches no person", subpopulation));
            }
        });
    }

    public void run() throws InterruptedException, ExecutionException {

        
        // we only collect the selected plans to perform mode-choice
        List<Plan> selectedPlans = new ArrayList<>(population.getPersons().size());
        Map<String, Integer> excludedCounts = new TreeMap<>();
        excludedSubpopulations.forEach(subpopulation -> excludedCounts.put(subpopulation, 0));
        for (Person person : population.getPersons().values()) {
            // remove unselected plans
            List<Plan> toRemove = new ArrayList<>();
            for (Plan plan : person.getPlans()) {
                if (plan != person.getSelectedPlan()) {
                    toRemove.add(plan);
                }
            }
            toRemove.forEach(person::removePlan);
            // persons of an excluded subpopulation keep their selected plan as read (no mode choice)
            String subpopulation = PopulationUtils.getSubpopulation(person);
            if (subpopulation != null && excludedSubpopulations.contains(subpopulation)) {
                excludedCounts.merge(subpopulation, 1, Integer::sum);
            } else {
                selectedPlans.add(person.getSelectedPlan());
            }
        }
        logExcludedSubpopulations(excludedCounts);

        // A worker exception propagates (ExecutionException from Future.get, or the exception itself without a
        // thread pool); the progress is closed on every path so a failed run ends instead of hanging (eqasim-bs#678).
        // An interruption while closing never replaces the worker's exception: try-with-resources adds it as suppressed.
        try (ParallelProgress progress = new ParallelProgress("Standalone mode choice", selectedPlans.size())) {
            progress.start();
            choose(selectedPlans, progress);
        }

        logger.info("Standalone mode choice: " + filteredTripCounter.summary()
                + " (persons without a valid alternative are not counted)");

        String outputPlansName = outputDirectoryHierarchy.getOutputFilename("output_plans.xml.gz");
        new PopulationWriter(population).write(outputPlansName);
        ConfigUtils.writeConfig(scenario.getConfig(), this.outputDirectoryHierarchy.getOutputFilename("output_config.xml"));
    }

    private void choose(List<Plan> selectedPlans, ParallelProgress progress) throws InterruptedException, ExecutionException {
        if(numberOfThreads > 0 && !selectedPlans.isEmpty()) {
        	ExecutorService exec = Executors.newFixedThreadPool(numberOfThreads);
            CompletionService<Set<Id<Person>>> completion = new ExecutorCompletionService<>(exec);

            final int total = selectedPlans.size();
            int numChunks = (total + chunkSize - 1) / chunkSize;

            logger.info(String.format(
                    "Splitting %d plans into %d chunks (%,d each) over %d threads",
                    total, numChunks, chunkSize, numberOfThreads));
            
            Random random = new Random(this.seed);
            for (int chunk = 0; chunk < numChunks; chunk++) {
                final int from = chunk * chunkSize;
                final int to   = Math.min(from + chunkSize, total);

                List<Plan> subList = selectedPlans.subList(from, to);
                Random chunkRandom = new Random(random.nextInt());

                completion.submit(() -> {
                    PlanAlgoThread worker = new PlanAlgoThread(
                        new DiscreteModeChoiceAlgorithm(
                            chunkRandom,
                            discreteModeChoiceModelProvider.get(),
                            population.getFactory(),
                            new TripListConverter()
                        ),
                        progress,
                        removePersonsWithBadPlans
                    );
                    // feed our little chunk
                    for (Plan plan : subList) {
                        worker.addPlanToThread(plan);
                    }
                    // run and collect any bad-plan IDs
                    worker.run();
                    return worker.getPersonsWithNoAlternative();
                });
            }

            // Take the chunk results as they complete, so the first failed chunk ends the choice at once instead of
            // after all remaining chunks have run (eqasim-bs#678). The combined set does not depend on the order in
            // which the chunks finish.
            IdSet<Person> personsToRemove = new IdSet<>(Person.class);
            try {
                for (int chunk = 0; chunk < numChunks; chunk++) {
                    personsToRemove.addAll(completion.take().get());
                }
            } catch (ExecutionException | InterruptedException e) {
                logger.error("Standalone mode choice failed in a chunk or was interrupted; cancelling the remaining chunks");
                throw e;
            } finally {
                // after success every chunk is done and the idle threads end; after a failure the chunks not yet
                // started are dropped and the running ones are interrupted (PlanAlgoThread stops at the next plan)
                exec.shutdownNow();
            }

            if(this.removePersonsWithBadPlans && !personsToRemove.isEmpty()) {

                double percentage = ((double) personsToRemove.size()) * 100 / population.getPersons().size();
                logger.info(String.format("Removing %d persons with no valid alternative out of %d (%f %%)", personsToRemove.size(), population.getPersons().size(), percentage));
                for(Id<Person> personId: personsToRemove) {
                    population.removePerson(personId);
                }
            }
        } else {
            Random random = new Random(this.seed);
            PlanAlgoThread planAlgoThread = new PlanAlgoThread(new DiscreteModeChoiceAlgorithm(random, this.discreteModeChoiceModelProvider.get(), this.population.getFactory(), new TripListConverter()), progress, this.removePersonsWithBadPlans);
            // the unselected plans are already removed and the excluded persons left out above
            selectedPlans.forEach(planAlgoThread::addPlanToThread);
            planAlgoThread.run();
            if(this.removePersonsWithBadPlans) {
                IdSet<Person> personsToRemove = new IdSet<>(Person.class);
                personsToRemove.addAll(planAlgoThread.getPersonsWithNoAlternative());
                double percentage = ((double) personsToRemove.size()) * 100 / population.getPersons().size();
                logger.info(String.format("Removing %d persons with no valid alternative out of %d (%f %%)", personsToRemove.size(), population.getPersons().size(), percentage));
                personsToRemove.forEach(population::removePerson);
            }
        }
    }


    private final static class PlanAlgoThread implements Runnable {

        private final DiscreteModeChoiceAlgorithm planAlgo;
        private final List<Plan> plans = new LinkedList<>();
        private final ParallelProgress progress;
        private final IdSet<Person> personsWithNoAlternative;
        private final boolean reportPersonsWithNoAlternative;

        public PlanAlgoThread(final DiscreteModeChoiceAlgorithm algo, final ParallelProgress progress, boolean reportPersonsWithNoAlternative) {
            this.planAlgo = algo;
            this.progress = progress;
            this.personsWithNoAlternative = new IdSet<>(Person.class);
            this.reportPersonsWithNoAlternative = reportPersonsWithNoAlternative;
        }

        public void addPlanToThread(final Plan plan) {
            this.plans.add(plan);
        }

        @Override
        public void run() {
            for (Plan plan : this.plans) {
                if (Thread.currentThread().isInterrupted()) {
                    // a cancelled chunk (another chunk failed) stops instead of choosing its remaining plans
                    throw new IllegalStateException("Standalone mode choice worker was interrupted");
                }
                try {
                    this.planAlgo.run(plan);
                } catch (IllegalStateException e) {
                    if(e.getCause() instanceof DiscreteModeChoiceModel.NoFeasibleChoiceException) {
                        if(this.reportPersonsWithNoAlternative) {
                            this.personsWithNoAlternative.add(plan.getPerson().getId());
                        }
                    } else {
                        throw e;
                    }
                }
                this.progress.update(1);
            }
        }


        public IdSet<Person> getPersonsWithNoAlternative() {
            return this.personsWithNoAlternative;
        }
    }
}
