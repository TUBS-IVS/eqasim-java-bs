package org.eqasim.braunschweig.mode_choice;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.braunschweig.mode_choice.utilities.predictors.BraunschweigPredictorUtils;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.mode_availability.ModeAvailability;

public class BraunschweigModeAvailability implements ModeAvailability {
	private static final Logger logger = LogManager.getLogger(BraunschweigModeAvailability.class);
	private static final String CAR_PASSENGER_AVAILABILITY_ATTRIBUTE = "carPassengerAvailability";
	private static final long PASSENGER_AVAILABILITY_LOG_INTERVAL = 1_000_000L;
	private final AtomicLong passengerAttributeCount = new AtomicLong();
	private final AtomicLong passengerCompatibilityCount = new AtomicLong();

	@Override
	public Collection<String> getAvailableModes(Person person, List<DiscreteModeChoiceTrip> trips) {
		// Freight agents (german-wide-freight injection) are trip creators, not
		// travelers: their single leg is fixed to "truck". Returning only that
		// mode makes any DMC replanning a no-op re-selection of the same mode,
		// regardless of how strategies are scoped to subpopulations.
		if ("freight".equals(org.matsim.core.population.PopulationUtils.getSubpopulation(person))) {
			return java.util.List.of("truck");
		}

		Collection<String> modes = new HashSet<>();

		// Modes that are always available
		modes.add(TransportMode.walk);
		modes.add(TransportMode.pt);

		// Check car availability
		if (hasCarPassengerAvailability(person)) {
			modes.add(BraunschweigModeChoiceModule.CAR_PASSENGER);
		}

		if (BraunschweigPredictorUtils.hasCarAvailability(person)) {
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

	private boolean hasCarPassengerAvailability(Person person) {
		Object value = person.getAttributes().getAttribute(CAR_PASSENGER_AVAILABILITY_ATTRIBUTE);
		if (!person.getAttributes().getAsMap().containsKey(CAR_PASSENGER_AVAILABILITY_ATTRIBUTE)) {
			long compatibility = passengerCompatibilityCount.incrementAndGet();
			logPassengerAvailabilityCoverage(passengerAttributeCount.get(), compatibility);
			return BraunschweigPredictorUtils.hasCarAvailability(person);
		}

		if (!(value instanceof String)) {
			throw new IllegalArgumentException(String.format(
					"Person %s has non-string %s value of type %s; expected one of none, some, all",
					person.getId(), CAR_PASSENGER_AVAILABILITY_ATTRIBUTE,
					value == null ? "null" : value.getClass().getName()));
		}

		boolean available = switch ((String) value) {
		case "none" -> false;
		case "some", "all" -> true;
		default -> throw new IllegalArgumentException(String.format(
				"Person %s has invalid %s value '%s'; expected one of none, some, all",
				person.getId(), CAR_PASSENGER_AVAILABILITY_ATTRIBUTE, value));
		};

		long primary = passengerAttributeCount.incrementAndGet();
		logPassengerAvailabilityCoverage(primary, passengerCompatibilityCount.get());
		return available;
	}

	private void logPassengerAvailabilityCoverage(long primary, long compatibility) {
		long total = primary + compatibility;
		if (primary == 1L || compatibility == 1L || total % PASSENGER_AVAILABILITY_LOG_INTERVAL == 0L) {
			logger.info("carPassengerAvailability evaluations: attribute {}/{}, compatibility fallback {}/{}",
					primary, total, compatibility, total);
		}
	}
}
