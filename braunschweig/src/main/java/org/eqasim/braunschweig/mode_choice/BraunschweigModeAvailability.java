package org.eqasim.braunschweig.mode_choice;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import org.eqasim.braunschweig.mode_choice.utilities.predictors.BraunschweigPredictorUtils;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.mode_availability.ModeAvailability;

public class BraunschweigModeAvailability implements ModeAvailability {
	@Override
	public Collection<String> getAvailableModes(Person person, List<DiscreteModeChoiceTrip> trips) {
		Collection<String> modes = new HashSet<>();

		// Modes that are always available
		modes.add(TransportMode.walk);
		modes.add(TransportMode.pt);

		// Check car availability
		if (BraunschweigPredictorUtils.hasCarAvailability(person)) {
			modes.add(BraunschweigModeChoiceModule.CAR_PASSENGER);

			if (BraunschweigPredictorUtils.hasDrivingLicense(person)) {
				modes.add(TransportMode.car);
			}
		}

		// Check bicycle availability
		if (BraunschweigPredictorUtils.hasBicycleAvailability(person)) {
			modes.add(BraunschweigModeChoiceModule.BICYCLE);
		}

		// Add special mode "outside" if applicable
		if (BraunschweigPredictorUtils.isOutside(person)) {
			modes.add("outside");
		}

		return modes;
	}
}
