package org.eqasim.braunschweig.fares.zonal;

import java.util.List;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;

/** Prices one routed PT trip; implemented by the cost model, mocked in the estimator tests. */
public interface FareQuoteSource {
	/** Prices the trip and records its outcome once in the per-iteration outcome counts. */
	FareQuote quote(Person person, List<? extends PlanElement> elements);

	/** Prices the trip without recording it, for re-pricing earlier trips of a prefix (day-ticket cap). */
	FareQuote quoteWithoutCounting(Person person, List<? extends PlanElement> elements);
}
