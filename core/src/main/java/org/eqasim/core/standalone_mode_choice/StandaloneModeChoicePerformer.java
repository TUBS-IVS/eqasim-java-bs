package org.eqasim.core.standalone_mode_choice;


import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
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
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.core.misc.ParallelProgress;
import org.eqasim.core.scenario.routing.RunPopulationRouting;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdSet;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceModel;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.replanning.DiscreteModeChoiceAlgorithm;
import org.matsim.contribs.discrete_mode_choice.replanning.TripListConverter;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.misc.Counter;

import com.google.inject.Provider;

public class StandaloneModeChoicePerformer {

    private static final Logger logger = LogManager.getLogger(StandaloneModeChoicePerformer.class);

    private final Provider<DiscreteModeChoiceModel> discreteModeChoiceModelProvider;
    private final Provider<FilteredTripFinder> filteredTripFinderProvider;
    private final boolean removePersonsWithBadPlans;
    private final Population population;
    private final int numberOfThreads;
    private final long seed;
    private final int chunkSize;
    private final OutputDirectoryHierarchy outputDirectoryHierarchy;
    private final Scenario scenario;
    private final Set<String> excludedSubpopulations;

    // trips of all chosen plans, and those of them kept unchanged because a filter rejects them (eqasim-bs#678)
    private final AtomicLong numberOfTrips = new AtomicLong();
    private final AtomicLong numberOfKeptTrips = new AtomicLong();

    /**
     * @param filteredTripFinderProvider finds the trips the model does not re-choose because a filter rejects them;
     *                                   they leave the mode choice exactly as they came in (eqasim-bs#678)
     */
    public StandaloneModeChoicePerformer(Provider<DiscreteModeChoiceModel> discreteModeChoiceModelProvider, Provider<FilteredTripFinder> filteredTripFinderProvider, StandaloneModeChoiceConfigGroup configGroup, Population population, int numberOfThreads, long seed, OutputDirectoryHierarchy outputDirectoryHierarchy, Scenario scenario) {
        this.discreteModeChoiceModelProvider = discreteModeChoiceModelProvider;
        this.filteredTripFinderProvider = filteredTripFinderProvider;
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

        long trips = numberOfTrips.get();
        long kept = numberOfKeptTrips.get();
        logger.info(String.format(Locale.ROOT,
                "Standalone mode choice: %d of %d trips (%.2f %%) are rejected by the model's filter, not re-chosen and kept unchanged"
                        + " (counted over all chosen plans, including persons removed afterwards for lack of a valid alternative)",
                kept, trips, trips == 0 ? 0.0 : 100.0 * kept / trips));

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
                        filteredTripFinderProvider.get(),
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
            PlanAlgoThread planAlgoThread = new PlanAlgoThread(new DiscreteModeChoiceAlgorithm(random, this.discreteModeChoiceModelProvider.get(), this.population.getFactory(), new TripListConverter()), this.filteredTripFinderProvider.get(), progress, this.removePersonsWithBadPlans);
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


    /** The elements of a trip as they came in, put back after the choice (eqasim-bs#678). */
    private record KeptTrip(Activity origin, Activity destination, List<PlanElement> elements) {
    }

    private final class PlanAlgoThread implements Runnable {

        private final DiscreteModeChoiceAlgorithm planAlgo;
        private final FilteredTripFinder filteredTripFinder;
        private final TripListConverter tripListConverter = new TripListConverter();
        private final List<Plan> plans = new LinkedList<>();
        private final ParallelProgress progress;
        private final IdSet<Person> personsWithNoAlternative;
        private final boolean reportPersonsWithNoAlternative;

        public PlanAlgoThread(final DiscreteModeChoiceAlgorithm algo, final FilteredTripFinder filteredTripFinder, final ParallelProgress progress, boolean reportPersonsWithNoAlternative) {
            this.planAlgo = algo;
            this.filteredTripFinder = filteredTripFinder;
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
                List<KeptTrip> keptTrips = findKeptTrips(plan);
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
                // The algorithm re-inserts every trip, also those the model keeps at their initial modes, re-routed by
                // the plain trip router (walk-fallback stamp lost, pt possibly walk-only): put them back as they came in
                for (KeptTrip keptTrip : keptTrips) {
                    TripRouter.insertTrip(plan, keptTrip.origin(), keptTrip.elements(), keptTrip.destination());
                }
                this.progress.update(1);
            }
        }

        /** The trips of the plan that the model does not re-choose, with their elements as they are now. */
        private List<KeptTrip> findKeptTrips(Plan plan) {
            List<DiscreteModeChoiceTrip> trips = tripListConverter.convert(plan);
            numberOfTrips.addAndGet(trips.size());
            List<DiscreteModeChoiceTrip> filtered = filteredTripFinder.findFilteredTrips(plan.getPerson(), trips);
            if (filtered.isEmpty()) {
                return List.of();
            }

            Map<Activity, TripStructureUtils.Trip> tripsByOrigin = new IdentityHashMap<>();
            for (TripStructureUtils.Trip trip : TripStructureUtils.getTrips(plan)) {
                tripsByOrigin.put(trip.getOriginActivity(), trip);
            }

            List<KeptTrip> kept = new ArrayList<>(filtered.size());
            for (DiscreteModeChoiceTrip filteredTrip : filtered) {
                TripStructureUtils.Trip trip = tripsByOrigin.get(filteredTrip.getOriginActivity());
                if (trip == null) {
                    throw new IllegalStateException(String.format(
                            "Person %s: the filtered trip from activity %s is not a trip of the plan",
                            plan.getPerson().getId(), filteredTrip.getOriginActivity().getType()));
                }
                kept.add(new KeptTrip(trip.getOriginActivity(), trip.getDestinationActivity(),
                        new ArrayList<>(trip.getTripElements())));
            }
            numberOfKeptTrips.addAndGet(kept.size());
            return kept;
        }

        public IdSet<Person> getPersonsWithNoAlternative() {
            return this.personsWithNoAlternative;
        }
    }
}
