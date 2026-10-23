package org.eqasim.core.scenario.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.core.misc.ParallelProgress;
import org.matsim.api.core.v01.population.Activity;
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
	/** Routed day-end shift (eqasim-bs ADR-0160); null when off. */
	private final DayEndShift dayEndShift;

	private Map<String, Integer> lastRoutedTripCounts = Map.of();
	private Map<String, Integer> lastWalkFallbackCounts = Map.of();
	private Map<String, Integer> lastKeptWalkFallbackCounts = Map.of();

	public PopulationRouter(int numberOfThreads, int batchSize, boolean replaceExistingRoutes, Set<String> modes,
			Provider<PlanRouter> routerProvider) {
		this(numberOfThreads, batchSize, replaceExistingRoutes, modes, routerProvider, null);
	}

	/**
	 * @param dayEndShift when not null, every routed plan whose last planned
	 *                    arrival is later than its latest arrival moves earlier
	 *                    as a whole and is routed again (eqasim-bs ADR-0160)
	 */
	public PopulationRouter(int numberOfThreads, int batchSize, boolean replaceExistingRoutes, Set<String> modes,
			Provider<PlanRouter> routerProvider, DayEndShift dayEndShift) {
		if (dayEndShift != null && !modes.isEmpty()) {
			// a shifted plan must be routed again as a whole: trips of other modes would keep routes for the old times
			throw new IllegalArgumentException("The routed day-end shift needs all modes routed; it cannot be "
					+ "combined with a mode restriction (" + modes + ")");
		}

		this.numberOfThreads = numberOfThreads;
		this.batchSize = batchSize;
		this.routerProvider = routerProvider;
		this.replaceExistingRoutes = replaceExistingRoutes;
		this.modes = modes;
		this.dayEndShift = dayEndShift;
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
							PlanRouter.CountSnapshot beforeRouting = dayEndShift == null ? null
									: router.snapshotCounts();
							router.run(plan, replaceExistingRoutes, modes);

							if (dayEndShift != null) {
								// The shifted plan is routed again with all its routes replaced. The counts of the
								// first pass are discarded (every trip of the written population counted once),
								// and trips the first pass replaced by a walk get their requested mode back, so it
								// is tried at the new departure times (eqasim-bs ADR-0160)
								dayEndShift.apply(plan, () -> {
									router.restoreCounts(beforeRouting);
									int fallbacksBefore = PlanRouter.countWalkFallbackTrips(plan, modes);
									List<Activity> restored = PlanRouter.restoreWalkFallbackModes(plan, modes);
									router.run(plan, true, modes);
									int restoredStillWalking = PlanRouter.countWalkFallbackTripsFrom(plan, restored);
									int fallbacksAfter = PlanRouter.countWalkFallbackTrips(plan, modes);
									dayEndShift.recordRestoredTrips(restored.size(),
											restored.size() - restoredStillWalking,
											fallbacksAfter - restoredStillWalking - (fallbacksBefore - restored.size()));
								});
							}
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
		lastRoutedTripCounts = sumCounts(allRouters, PlanRouter::getRoutedTripCounts);
		lastWalkFallbackCounts = sumCounts(allRouters, PlanRouter::getWalkFallbackCounts);
		lastKeptWalkFallbackCounts = sumCounts(allRouters, PlanRouter::getKeptWalkFallbackCounts);

		if (dayEndShift != null) {
			logDayEndShift(dayEndShift);
		}

		if (errorOccurred.get()) {
			throw new RuntimeException("Found errors in routing threads");
		}
	}

	/**
	 * Logs the routed day-end shift as rates; WARN when a plan could not move far
	 * enough or still ends too late after its re-routing (eqasim-bs ADR-0160).
	 */
	private static void logDayEndShift(DayEndShift shift) {
		long total = shift.getPlans();
		String message = String.format(Locale.ROOT,
				"[PopulationRouter] day end: routed last arrival after %.2f h for %d of %d plans (%.2f %%); moved "
						+ "earlier as a whole day and routed again: %d, mean shift %.0f s, max %d s; unresolved (no "
						+ "room before 0 s, or still late after the re-routing): %d; walk-fallback trips of shifted "
						+ "plans given their requested mode back: %d, of which routed by it after the shift: %d (the "
						+ "shift is sized by the first-pass routes, so these plans may have moved more than needed); "
						+ "trips of shifted plans that newly fell back to walk at the earlier time: %d; legs replayed "
						+ "without a travel time (counted as 0 s): %d",
				shift.getLatestLastArrival() / 3600.0, shift.getLatePlans(), total,
				total == 0 ? 0.0 : 100.0 * shift.getLatePlans() / total, shift.getShiftedPlans(),
				shift.getMeanShiftSeconds(), shift.getMaxShiftSeconds(), shift.getUnresolvedPlans(),
				shift.getRestoredTrips(), shift.getRecoveredTrips(), shift.getNewWalkFallbackTrips(),
				shift.getLegsWithoutTravelTime());

		if (shift.getUnresolvedPlans() > 0 || shift.getLegsWithoutTravelTime() > 0) {
			logger.warn(message);
		} else {
			logger.info(message);
		}
	}

	/** Trips routed by the last {@link #run}, per main mode, summed over the routing threads (as logged). */
	public Map<String, Integer> getRoutedTripCounts() {
		return lastRoutedTripCounts;
	}

	/** Trips replaced by a walk in the last {@link #run}, per requested mode (as logged). */
	public Map<String, Integer> getWalkFallbackCounts() {
		return lastWalkFallbackCounts;
	}

	/** Stamped walk trips of an earlier pass kept by the last {@link #run}, per requested mode (as logged). */
	public Map<String, Integer> getKeptWalkFallbackCounts() {
		return lastKeptWalkFallbackCounts;
	}

	/** Sums the per-mode counts of several routers, as selected by the accessor. */
	static Map<String, Integer> sumCounts(Iterable<PlanRouter> routers,
			Function<PlanRouter, Map<String, Integer>> accessor) {
		Map<String, Integer> sum = new TreeMap<>();
		for (PlanRouter router : routers) {
			accessor.apply(router).forEach((mode, count) -> sum.merge(mode, count, Integer::sum));
		}
		return sum;
	}

	/**
	 * Logs, per main mode, how many routed trips were replaced by a plain walk,
	 * and, per stamped mode, how many re-routed walk trips kept the stamp of an
	 * earlier routing pass (eqasim-bs#487).
	 */
	private static void logWalkFallback(Iterable<PlanRouter> routers) {
		Map<String, Integer> routed = sumCounts(routers, PlanRouter::getRoutedTripCounts);
		Map<String, Integer> fallback = sumCounts(routers, PlanRouter::getWalkFallbackCounts);
		Map<String, Integer> kept = sumCounts(routers, PlanRouter::getKeptWalkFallbackCounts);

		for (Map.Entry<String, Integer> entry : routed.entrySet()) {
			int total = entry.getValue();
			int replaced = fallback.getOrDefault(entry.getKey(), 0);
			logger.info(String.format(Locale.ROOT, "[PlanRouter] walk fallback: %s %d/%d (%.1f %%)", entry.getKey(),
					replaced, total, total == 0 ? 0.0 : 100.0 * replaced / total));
		}

		for (Map.Entry<String, Integer> entry : kept.entrySet()) {
			logger.info(String.format(Locale.ROOT,
					"[PlanRouter] walk fallback of an earlier routing pass kept: %s %d re-routed walk trips",
					entry.getKey(), entry.getValue()));
		}
	}
}
