package org.eqasim.braunschweig.mode_choice.constraints;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.eqasim.braunschweig.mode_choice.BraunschweigModeChoiceModule;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripConstraint;
import org.matsim.core.population.PopulationUtils;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

/**
 * Pins the rule for trips into a stroll activity (eqasim-bs#679): only a destination activity whose attribute
 * {@code outAndBackStroll} is the Boolean {@code true} limits the trip to the modes of the same-facility leisure rule.
 */
public class StrollTripConstraintTest {
	private static final List<String> ALL_MODES = List.of(TransportMode.car, BraunschweigModeChoiceModule.CAR_PASSENGER,
			TransportMode.pt, TransportMode.walk, BraunschweigModeChoiceModule.BICYCLE);

	@Test
	public void marked_trip_rejects_car_and_accepts_walk_and_bicycle() {
		DiscreteModeChoiceTrip trip = trip(Boolean.TRUE);
		TripConstraint constraint = createConstraint(trip);

		for (String mode : ALL_MODES) {
			assertEquals(mode, SameFacilityLeisureTripConstraint.ALLOWED_MODES.contains(mode),
					constraint.validateBeforeEstimation(trip, mode, List.of()));
		}

		assertFalse(constraint.validateBeforeEstimation(trip, TransportMode.car, List.of()));
		assertTrue(constraint.validateBeforeEstimation(trip, TransportMode.walk, List.of()));
		assertTrue(constraint.validateBeforeEstimation(trip, BraunschweigModeChoiceModule.BICYCLE, List.of()));
	}

	@Test
	public void unmarked_trip_accepts_every_mode() {
		assertEveryModeAllowed(trip(null));
	}

	@Test
	public void string_true_or_boolean_false_places_no_restriction() {
		assertEveryModeAllowed(trip("true"));
		assertEveryModeAllowed(trip(Boolean.FALSE));
	}

	@Test
	public void counts_caught_and_evaluated_trips() {
		StrollTripConstraint.Factory factory = new StrollTripConstraint.Factory();
		DiscreteModeChoiceTrip marked = trip(Boolean.TRUE);
		DiscreteModeChoiceTrip unmarked = trip(null);
		TripConstraint constraint = factory.createConstraint(person(), List.of(marked, unmarked), ALL_MODES);

		assertEquals(2, factory.getTrips());
		assertEquals(1, factory.getCaughtTrips());
		assertEquals(0, factory.getReachedTrips());

		constraint.validateBeforeEstimation(marked, TransportMode.car, List.of());
		constraint.validateBeforeEstimation(marked, TransportMode.walk, List.of());

		assertEquals(1, factory.getReachedTrips());
		assertEquals(1, factory.getRejectedCandidates());
	}

	private static void assertEveryModeAllowed(DiscreteModeChoiceTrip trip) {
		TripConstraint constraint = createConstraint(trip);

		for (String mode : ALL_MODES) {
			assertTrue(mode, constraint.validateBeforeEstimation(trip, mode, List.of()));
		}
	}

	private static TripConstraint createConstraint(DiscreteModeChoiceTrip trip) {
		return new StrollTripConstraint.Factory().createConstraint(person(), List.of(trip), ALL_MODES);
	}

	private static Person person() {
		return PopulationUtils.getFactory().createPerson(Id.createPersonId("person"));
	}

	/** A trip home to leisure; the out-and-back stroll attribute of the destination is set when the value is not null. */
	private static DiscreteModeChoiceTrip trip(Object strollValue) {
		Activity origin = PopulationUtils.createActivityFromCoord("home", new Coord(0, 0));
		Activity destination = PopulationUtils.createActivityFromCoord("leisure", new Coord(100, 0));

		if (strollValue != null) {
			destination.getAttributes().putAttribute(StrollTripConstraint.OUT_AND_BACK_STROLL_ATTRIBUTE, strollValue);
		}

		return new DiscreteModeChoiceTrip(origin, destination, TransportMode.car, List.of(), 0, 0, 0,
				new AttributesImpl());
	}
}
