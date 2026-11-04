package org.eqasim.core.simulation.mode_choice.trip_cache;

import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripEstimator;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;

/**
 * Trip estimate cache of the discrete mode choice, with the answers of the DMC contrib's CachedTripEstimator but a
 * lookup cost that does not grow over the iteration.
 *
 * <p>Like CachedTripEstimator, it answers a repeated estimate of the same trip object, mode and departure second
 * (the ceiling of the departure time) with the first estimate, for the cached modes only; other modes go to the
 * delegate on every call, and the previous trips of the request are not part of the key. Two things differ:
 * <ul>
 * <li>The trips are looked up by identity in an IdentityHashMap. CachedTripEstimator uses a HashMap, and
 * DiscreteModeChoiceTrip (matsim contrib discrete_mode_choice 2026.0-2026w12) gives every trip the same hash code: it
 * folds two int arguments with {@code a ^ (a >>> 32)}, and an int shift by 32 is a shift by 0 in Java, so the fold is
 * 0. Its equals is the identity of Object. All trips fall into one HashMap bin, and a lookup of a trip there scans
 * the trips already cached.</li>
 * <li>The cache keeps the estimates of the current person only and drops them when the estimate of another person is
 * asked. The DMC replanning converts a plan into new DiscreteModeChoiceTrip objects on each run (TripListConverter),
 * so an identity key of an earlier person can never be hit again; CachedTripEstimator keeps those entries until its
 * replanning thread ends with the iteration.</li>
 * </ul>
 * Both changes only remove entries that no later request can hit, so every answer equals the one of
 * CachedTripEstimator. Without them the replanning of one thread costs a time that grows with the square of its
 * persons: harmless when a few percent of the agents replan, 7 h 37 min of replanning in iteration 1 of the eqasim-bs
 * 100 % production run prod100_20261031, where every agent replans once (initial mode choice, eqasim-bs ADR-0155).
 *
 * <p>An instance is not thread-safe; like CachedTripEstimator it is created per injection, so each replanning thread
 * owns one.
 */
public final class PersonScopedCachedTripEstimator implements TripEstimator {
	private final TripEstimator delegate;
	private final Map<String, Map<DiscreteModeChoiceTrip, Map<Long, TripCandidate>>> cache = new HashMap<>();
	private Person currentPerson = null;

	public PersonScopedCachedTripEstimator(TripEstimator delegate, Collection<String> cachedModes) {
		this.delegate = delegate;
		for (String mode : cachedModes) {
			cache.put(mode, new IdentityHashMap<>());
		}
	}

	@Override
	public TripCandidate estimateTrip(Person person, String mode, DiscreteModeChoiceTrip trip,
			List<TripCandidate> previousTrips) {
		Map<DiscreteModeChoiceTrip, Map<Long, TripCandidate>> modeCache = cache.get(mode);
		if (modeCache == null) {
			return delegate.estimateTrip(person, mode, trip, previousTrips);
		}

		if (person != currentPerson) {
			// The trips of the previous person cannot be asked again (see the class comment): drop them.
			for (Map<DiscreteModeChoiceTrip, Map<Long, TripCandidate>> entries : cache.values()) {
				entries.clear();
			}
			currentPerson = person;
		}

		// The departure second as CachedTripEstimator keys it
		long departureSecond = (long) Math.ceil(trip.getDepartureTime());
		Map<Long, TripCandidate> tripCache = modeCache.computeIfAbsent(trip, ignored -> new HashMap<>());
		TripCandidate candidate = tripCache.get(departureSecond);
		if (candidate == null) {
			candidate = delegate.estimateTrip(person, mode, trip, previousTrips);
			tripCache.put(departureSecond, candidate);
		}
		return candidate;
	}

	/** The number of cached estimates (trip, mode and departure second), for tests. */
	int cachedEstimateCount() {
		int count = 0;
		for (Map<DiscreteModeChoiceTrip, Map<Long, TripCandidate>> modeCache : cache.values()) {
			for (Map<Long, TripCandidate> tripCache : modeCache.values()) {
				count += tripCache.size();
			}
		}
		return count;
	}
}
