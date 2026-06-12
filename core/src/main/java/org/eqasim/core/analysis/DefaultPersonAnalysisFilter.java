package org.eqasim.core.analysis;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

public class DefaultPersonAnalysisFilter implements PersonAnalysisFilter {
	@Override
	public boolean analyzePerson(Id<Person> personId) {
		// Exclude non-resident helper agents from the person-travel analysis:
		// the synthetic transit drivers ("pt_") and any injected long-haul
		// freight agents ("freight_" / subpopulation "freight"). Freight agents
		// are trip creators, not residents -- including them would skew every
		// person-travel KPI, and their coordinate-only dummy activities carry no
		// facility id (the ActivityWriter NPEs on them). Scenarios without a
		// freight subpopulation are unaffected.
		String id = personId.toString();
		return !id.startsWith("pt_") && !id.startsWith("freight_");
	}
}
