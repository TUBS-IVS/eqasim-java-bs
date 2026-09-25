package org.eqasim.braunschweig.fares.zonal;

import org.matsim.api.core.v01.population.Person;

/** Converts the long-distance flat price into equivalent PT in-vehicle seconds for one person and trip. */
public interface SurchargeValuation {
	/**
	 * @param person the routed person
	 * @param tripDistanceKm straight-line distance between origin and destination of the trip, in km
	 * @return the surcharge in seconds of PT in-vehicle time
	 */
	double surchargeSeconds(Person person, double tripDistanceKm);

	/** The surcharge of the reference person at the reference distance, used when no trip is known. */
	double referenceSurchargeSeconds();
}
