package org.eqasim.core.simulation.mode_choice.trip_cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.estimation.CachedTripEstimator;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripEstimator;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.DefaultTripCandidate;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;
import org.matsim.core.population.PopulationUtils;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

public class PersonScopedCachedTripEstimatorTest {
	private static final Set<String> CACHED_MODES = Set.of("car", "pt", "walk");
	private static final List<String> ALL_MODES = List.of("car", "pt", "walk", "bike");

	/**
	 * The same request sequence through the DMC CachedTripEstimator and through the person-scoped cache: every answer
	 * comes from the same delegate call, so hits, misses and returned candidates are identical. The sequence mimics
	 * the DMC replanning of one thread: persons one after another, each with fresh trip objects, repeated estimates of
	 * a trip per mode chain, departure times that move with the chain, and one mode outside the cached modes.
	 */
	@Test
	public void testAnswersEqualTheDmcCacheCallByCall() {
		CountingEstimator dmcDelegate = new CountingEstimator();
		CountingEstimator scopedDelegate = new CountingEstimator();
		TripEstimator dmcCache = new CachedTripEstimator(dmcDelegate, CACHED_MODES);
		TripEstimator scopedCache = new PersonScopedCachedTripEstimator(scopedDelegate, CACHED_MODES);

		Random random = new Random(42);
		int requests = 0;
		for (int personIndex = 0; personIndex < 200; personIndex++) {
			Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId("p" + personIndex));
			List<DiscreteModeChoiceTrip> trips = createTrips(1 + random.nextInt(5));
			for (int lookup = 0; lookup < 60; lookup++) {
				DiscreteModeChoiceTrip trip = trips.get(random.nextInt(trips.size()));
				// Fractional departure times: the DMC cache keys them by the ceiling second
				trip.setDepartureTime(28800.0 + random.nextInt(4) * 0.4);
				String mode = ALL_MODES.get(random.nextInt(ALL_MODES.size()));

				TripCandidate expected = dmcCache.estimateTrip(person, mode, trip, Collections.emptyList());
				TripCandidate actual = scopedCache.estimateTrip(person, mode, trip, Collections.emptyList());

				assertEquals("request " + requests, CountingEstimator.callOf(expected), CountingEstimator.callOf(actual));
				assertEquals(expected.getUtility(), actual.getUtility(), 0.0);
				assertEquals(expected.getMode(), actual.getMode());
				requests++;
			}
		}

		assertEquals(dmcDelegate.calls, scopedDelegate.calls);
	}

	@Test
	public void testRepeatedEstimateOfOneTripIsAnsweredFromTheCache() {
		CountingEstimator delegate = new CountingEstimator();
		TripEstimator cache = new PersonScopedCachedTripEstimator(delegate, CACHED_MODES);
		Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId("p"));
		DiscreteModeChoiceTrip trip = createTrips(1).get(0);
		trip.setDepartureTime(28800.0);

		TripCandidate first = cache.estimateTrip(person, "car", trip, Collections.emptyList());
		TripCandidate second = cache.estimateTrip(person, "car", trip, Collections.emptyList());
		cache.estimateTrip(person, "bike", trip, Collections.emptyList());
		cache.estimateTrip(person, "bike", trip, Collections.emptyList());

		assertSame(first, second);
		assertEquals("one car call, two uncached bike calls", 3, delegate.calls);
	}

	/**
	 * The trip objects of a person are created for one replanning of that person only, so no later person can hit
	 * them; the cache drops them when the next person comes. Its size, and with it the cost of a lookup, stays bounded
	 * by one person's trips instead of growing with every person the thread replans.
	 */
	@Test
	public void testCacheHoldsOnlyTheCurrentPersonsTrips() {
		PersonScopedCachedTripEstimator cache = new PersonScopedCachedTripEstimator(new CountingEstimator(), CACHED_MODES);

		for (int personIndex = 0; personIndex < 1000; personIndex++) {
			Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId("p" + personIndex));
			for (DiscreteModeChoiceTrip trip : createTrips(3)) {
				trip.setDepartureTime(28800.0);
				for (String mode : CACHED_MODES) {
					cache.estimateTrip(person, mode, trip, Collections.emptyList());
				}
			}
			assertEquals(3 * CACHED_MODES.size(), cache.cachedEstimateCount());
		}
	}

	static List<DiscreteModeChoiceTrip> createTrips(int count) {
		List<DiscreteModeChoiceTrip> trips = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			Activity origin = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId("o" + index));
			Activity destination = PopulationUtils.createActivityFromLinkId("work", Id.createLinkId("d" + index));
			trips.add(new DiscreteModeChoiceTrip(origin, destination, "walk", Collections.emptyList(), 0, index, index,
					new AttributesImpl()));
		}
		return trips;
	}

	/** Answers every call with a new candidate that records the number of the call that produced it. */
	static final class CountingEstimator implements TripEstimator {
		int calls = 0;

		@Override
		public TripCandidate estimateTrip(Person person, String mode, DiscreteModeChoiceTrip trip,
				List<TripCandidate> previousTrips) {
			calls++;
			return new DefaultTripCandidate(calls, mode, 600.0);
		}

		static int callOf(TripCandidate candidate) {
			return (int) candidate.getUtility();
		}
	}
}
