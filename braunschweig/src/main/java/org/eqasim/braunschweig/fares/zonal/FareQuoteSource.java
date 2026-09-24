package org.eqasim.braunschweig.fares.zonal;

import java.util.List;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;

/** Prices one routed PT trip; implemented by the cost model, mocked in the estimator tests. */
public interface FareQuoteSource {
	FareQuote quote(Person person, List<? extends PlanElement> elements);
}
