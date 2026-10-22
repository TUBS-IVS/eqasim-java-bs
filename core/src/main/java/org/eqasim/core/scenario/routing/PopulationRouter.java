package org.eqasim.core.scenario.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eqasim.core.misc.ParallelProgress;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;

import com.google.inject.Provider;

public class PopulationRouter {
	private static final Logger logger = LogManager.getLogger(PopulationRouter.class);

	private final int numberOfThreads;
	private final int batchSize;
	private final boolean replaceExistingRoutes;
	private final Provider<PlanRouter> routerProvider;
	private final Set<String> modes;

	public PopulationRouter(int numberOfThreads, int batchSize, boolean replaceExistingRoutes, Set<String> modes,
			Provider<PlanRouter> routerProvider) {
		this.numberOfThreads = numberOfThreads;
		this.batchSize = batchSize;
		this.routerProvider = routerProvider;
		this.replaceExistingRoutes = replaceExistingRoutes;
		this.modes = modes;
	}

	public void run(Population population) throws InterruptedException {
		List<Person> allPersons = new ArrayList<>(population.getPersons().values());
		int total = allPersons.size();
		
		ParallelProgress progress = new ParallelProgress("Population routing", total);
		progress.start();

		ExecutorService exec = Executors.newFixedThreadPool(numberOfThreads);
		AtomicBoolean errorOccurred = new AtomicBoolean(false);

		final Object lock = new Object();

		// All thread-local routers, kept to sum their walk fallback counts afterwards
		ConcurrentLinkedQueue<PlanRouter> allRouters = new ConcurrentLinkedQueue<>();

		ThreadLocal<PlanRouter> routerLocal =
		    ThreadLocal.withInitial(() -> {
		        synchronized (lock) {
		            PlanRouter created = routerProvider.get();
		            allRouters.add(created);
		            return created; // constructed one-at-a-time to avoid concurrent modifications of the network due to turn restrictions
		        }
		    });

		// Build independent chunk lists
		List<List<Person>> chunks = new ArrayList<>();
		for (int i = 0; i < total; i += batchSize) {
			int end = Math.min(i + batchSize, total);
			chunks.add(new ArrayList<>(allPersons.subList(i, end)));
		}

		// Submit one task per chunk
		for (List<Person> chunk : chunks) {
			exec.submit(() -> {
				try {
					PlanRouter router = routerLocal.get();
					for (Person p : chunk) {
						for (Plan plan : p.getPlans()) {
							router.run(plan, replaceExistingRoutes, modes);
						}
					}
					progress.update(chunk.size());

				} catch (Exception e) {
					e.printStackTrace();
					errorOccurred.set(true);
				}
			});
		}

		exec.shutdown();
		exec.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
		
		progress.close();

		logWalkFallback(allRouters);

		if (errorOccurred.get()) {
			throw new RuntimeException("Found errors in routing threads");
		}
	}

	/** Sums the per-mode counts of several routers, as selected by the accessor. */
	static Map<String, Integer> sumCounts(Iterable<PlanRouter> routers,
			java.util.function.Function<PlanRouter, Map<String, Integer>> accessor) {
		Map<String, Integer> sum = new TreeMap<>();
		for (PlanRouter router : routers) {
			accessor.apply(router).forEach((mode, count) -> sum.merge(mode, count, Integer::sum));
		}
		return sum;
	}

	/** Logs, per main mode, how many routed trips were replaced by a plain walk. */
	private static void logWalkFallback(Iterable<PlanRouter> routers) {
		Map<String, Integer> routed = sumCounts(routers, PlanRouter::getRoutedTripCounts);
		Map<String, Integer> fallback = sumCounts(routers, PlanRouter::getWalkFallbackCounts);

		for (Map.Entry<String, Integer> entry : routed.entrySet()) {
			int total = entry.getValue();
			int replaced = fallback.getOrDefault(entry.getKey(), 0);
			logger.info(String.format(Locale.ROOT, "[PlanRouter] walk fallback: %s %d/%d (%.1f %%)", entry.getKey(),
					replaced, total, total == 0 ? 0.0 : 100.0 * replaced / total));
		}
	}
}
