package org.eqasim.braunschweig.fares.zonal;

import java.util.OptionalDouble;

import org.matsim.api.core.v01.population.Person;

/**
 * Hands the long-distance routing surcharge valued for the current PT routing request from
 * {@link LongDistanceSurchargeStopFinder} (which sees the request's origin, destination and person) to
 * {@link LongDistanceFareRaptorCostCalculator} (which sees only the person and the vehicle). SwissRailRaptor
 * finds the access stops and then runs its search in the calling thread, so a per-thread value set at stop
 * finding is the value of that request; it is only returned for the same person object.
 */
public final class LongDistanceSurchargeContext {
	private record Entry(Person person, double surchargeSeconds) {
	}

	private final ThreadLocal<Entry> current = new ThreadLocal<>();

	public void set(Person person, double surchargeSeconds) {
		current.set(new Entry(person, surchargeSeconds));
	}

	public OptionalDouble surchargeSecondsFor(Person person) {
		Entry entry = current.get();
		return entry != null && entry.person() == person ? OptionalDouble.of(entry.surchargeSeconds()) : OptionalDouble.empty();
	}
}
