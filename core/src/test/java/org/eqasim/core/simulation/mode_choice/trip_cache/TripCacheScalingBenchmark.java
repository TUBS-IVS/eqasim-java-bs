package org.eqasim.core.simulation.mode_choice.trip_cache;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.estimation.CachedTripEstimator;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripEstimator;
import org.matsim.core.population.PopulationUtils;

/**
 * Micro-benchmark of the trip estimate cache cost in one replanning thread, not run by the test suite (no Test
 * suffix): the DMC contrib's CachedTripEstimator against PersonScopedCachedTripEstimator, for a growing number of
 * persons. The delegate costs nothing, so the times are the cache overhead alone.
 *
 * <p>Per person: 4 fresh trips; per trip and cached mode (6, as in the Braunschweig config), 3 estimates at the same
 * departure second (cache hits, as for repeated mode chains) and 1 at another second. The eqasim-bs production run
 * prod100_20261031 replanned about 1.15 million agents on 56 threads in iteration 1, about 20,500 persons per thread.
 *
 * <p>Run: {@code java -cp <test classpath> org.eqasim.core.simulation.mode_choice.trip_cache.TripCacheScalingBenchmark}
 */
public final class TripCacheScalingBenchmark {
	private static final Set<String> MODES = Set.of("bicycle", "pt", "car", "truck", "car_passenger", "walk");
	private static final int TRIPS_PER_PERSON = 4;

	private TripCacheScalingBenchmark() {
	}

	public static void main(String[] args) {
		int[] personCounts = args.length > 0 ? Arrays.stream(args).mapToInt(Integer::parseInt).toArray()
				: new int[] { 1250, 2500, 5000, 10000 };
		// Warm-up of both caches at the smallest size
		run(new CachedTripEstimator(new PersonScopedCachedTripEstimatorTest.CountingEstimator(), MODES), 1250);
		run(new PersonScopedCachedTripEstimator(new PersonScopedCachedTripEstimatorTest.CountingEstimator(), MODES),
				1250);

		System.out.println("persons,dmc_cached_trip_estimator_ms,person_scoped_ms,delegate_calls_dmc,delegate_calls_scoped");
		for (int persons : personCounts) {
			PersonScopedCachedTripEstimatorTest.CountingEstimator dmcDelegate = new PersonScopedCachedTripEstimatorTest.CountingEstimator();
			PersonScopedCachedTripEstimatorTest.CountingEstimator scopedDelegate = new PersonScopedCachedTripEstimatorTest.CountingEstimator();
			double dmcMs = run(new CachedTripEstimator(dmcDelegate, MODES), persons);
			double scopedMs = run(new PersonScopedCachedTripEstimator(scopedDelegate, MODES), persons);
			System.out.printf(Locale.ROOT, "%d,%.1f,%.1f,%d,%d%n", persons, dmcMs, scopedMs, dmcDelegate.calls,
					scopedDelegate.calls);
		}
	}

	private static double run(TripEstimator estimator, int persons) {
		long start = System.nanoTime();
		for (int personIndex = 0; personIndex < persons; personIndex++) {
			Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId("p" + personIndex));
			List<DiscreteModeChoiceTrip> trips = PersonScopedCachedTripEstimatorTest.createTrips(TRIPS_PER_PERSON);
			for (DiscreteModeChoiceTrip trip : trips) {
				for (String mode : MODES) {
					trip.setDepartureTime(28800.0);
					for (int repeat = 0; repeat < 3; repeat++) {
						estimator.estimateTrip(person, mode, trip, Collections.emptyList());
					}
					trip.setDepartureTime(29400.0);
					estimator.estimateTrip(person, mode, trip, Collections.emptyList());
				}
			}
		}
		return (System.nanoTime() - start) / 1e6;
	}
}
