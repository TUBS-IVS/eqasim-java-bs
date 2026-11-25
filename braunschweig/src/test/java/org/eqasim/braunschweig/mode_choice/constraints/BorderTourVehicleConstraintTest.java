package org.eqasim.braunschweig.mode_choice.constraints;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.components.constraints.VehicleTourConstraint;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourConstraint;
import org.matsim.core.population.PopulationUtils;
import org.matsim.facilities.ActivityFacility;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

/**
 * Pins the vehicle rule for border tours (eqasim-bs#678, spec section 5.2, cases of section 7): outside border tours
 * the stock {@link VehicleTourConstraint} decides; inside them vehicle legs keep their mode, no new vehicle leg is
 * admitted, the other modes are free and the initial chain is always valid.
 */
public class BorderTourVehicleConstraintTest {
	private static final List<String> RESTRICTED_MODES = List.of("car", "bicycle");
	private static final List<String> ALL_MODES = List.of("car", "bicycle", "pt", "walk", "car_passenger");
	private static final Id<ActivityFacility> HOME_ID = Id.create("home", ActivityFacility.class);

	@Test
	public void borderTourFlags_markEveryTripOfATourThatTouchesOutside() {
		List<DiscreteModeChoiceTrip> trips = plan("HWOLHSH", "walk", "walk", "walk", "walk", "walk", "walk");

		assertArrayEquals(new boolean[] { true, true, true, true, false, false },
				BorderTourVehicleConstraint.borderTourFlags(trips));
	}

	@Test
	public void borderTourFlags_coverAPlanThatDoesNotStartAtHome() {
		List<DiscreteModeChoiceTrip> trips = plan("LWOSH", "walk", "walk", "walk", "walk");

		assertArrayEquals(new boolean[] { true, true, true, true }, BorderTourVehicleConstraint.borderTourFlags(trips));
	}

	@Test
	public void ordinaryTour_decidesLikeTheStockConstraint() {
		List<DiscreteModeChoiceTrip> trips = plan("HWSH", "car", "car", "car");
		TourConstraint constraint = createConstraint(trips);
		VehicleTourConstraint stock = new VehicleTourConstraint(RESTRICTED_MODES, HOME_ID);

		List<List<String>> candidates = List.of(List.of("car", "car", "car"), List.of("car", "walk", "car"),
				List.of("walk", "car", "car"), List.of("walk", "walk", "walk"));

		for (List<String> modes : candidates) {
			assertEquals(modes.toString(), stock.validateBeforeEstimation(trips, modes, List.of()),
					constraint.validateBeforeEstimation(trips, modes, List.of()));
		}
	}

	@Test
	public void borderTour_keepsVehicleLegsAndAddsNone() {
		List<DiscreteModeChoiceTrip> trips = plan("HABOH", "car", "walk", "pt", "pt");
		TourConstraint constraint = createConstraint(trips);
		List<DiscreteModeChoiceTrip> tour = trips.subList(0, 2);

		assertTrue(constraint.validateBeforeEstimation(tour, List.of("car", "walk"), List.of()));
		assertTrue(constraint.validateBeforeEstimation(tour, List.of("car", "pt"), List.of()));
		assertTrue(constraint.validateBeforeEstimation(tour, List.of("car", "car_passenger"), List.of()));

		assertFalse(constraint.validateBeforeEstimation(tour, List.of("walk", "walk"), List.of()));
		assertFalse(constraint.validateBeforeEstimation(tour, List.of("car", "car"), List.of()));
		assertFalse(constraint.validateBeforeEstimation(tour, List.of("car", "bicycle"), List.of()));
	}

	@Test
	public void borderTour_initialChainIsAlwaysValid() {
		// A car leg that does not start at home: the stock constraint would reject this chain.
		List<DiscreteModeChoiceTrip> trips = plan("HABOH", "walk", "car", "pt", "pt");
		TourConstraint constraint = createConstraint(trips);
		List<DiscreteModeChoiceTrip> tour = trips.subList(0, 2);

		assertFalse(new VehicleTourConstraint(RESTRICTED_MODES, HOME_ID).validateBeforeEstimation(tour,
				List.of("walk", "car"), List.of()));
		assertTrue(constraint.validateBeforeEstimation(tour, List.of("walk", "car"), List.of()));
	}

	@Test
	public void borderTour_aCarLegToTheBorderStaysCar() {
		List<DiscreteModeChoiceTrip> trips = plan("HAOH", "car", "car", "car");
		TourConstraint constraint = createConstraint(trips);
		List<DiscreteModeChoiceTrip> tour = trips.subList(0, 1);

		assertTrue(constraint.validateBeforeEstimation(tour, List.of("car"), List.of()));
		assertFalse(constraint.validateBeforeEstimation(tour, List.of("walk"), List.of()));
		assertFalse(constraint.validateBeforeEstimation(tour, List.of("pt"), List.of()));
	}

	@Test
	public void borderTour_vehicleLegWithoutAvailabilityIsAFreeLegAndCounted() {
		// The initial plan has a car leg in a border tour, but car is not an available mode of the person.
		List<DiscreteModeChoiceTrip> trips = plan("HABOH", "car", "walk", "pt", "pt");
		BorderTourVehicleConstraint.Factory factory = createFactory();
		TourConstraint constraint = factory.createConstraint(person(), trips,
				List.of("bicycle", "pt", "walk", "car_passenger"));
		List<DiscreteModeChoiceTrip> tour = trips.subList(0, 2);

		assertTrue(constraint.validateBeforeEstimation(tour, List.of("walk", "walk"), List.of()));
		assertTrue(constraint.validateBeforeEstimation(tour, List.of("pt", "walk"), List.of()));
		assertTrue(constraint.validateBeforeEstimation(tour, List.of("car_passenger", "walk"), List.of()));

		assertFalse(constraint.validateBeforeEstimation(tour, List.of("car", "walk"), List.of()));
		assertFalse(constraint.validateBeforeEstimation(tour, List.of("bicycle", "walk"), List.of()));

		assertEquals(1, factory.getBorderTourVehicleTrips());
		assertEquals(1, factory.getRelaxedVehicleTrips());
	}

	@Test
	public void borderTour_vehicleLegWithAvailabilityIsUnchanged() {
		List<DiscreteModeChoiceTrip> trips = plan("HABOH", "car", "walk", "pt", "pt");
		BorderTourVehicleConstraint.Factory factory = createFactory();
		TourConstraint constraint = factory.createConstraint(person(), trips, ALL_MODES);
		List<DiscreteModeChoiceTrip> tour = trips.subList(0, 2);

		assertTrue(constraint.validateBeforeEstimation(tour, List.of("car", "walk"), List.of()));
		assertFalse(constraint.validateBeforeEstimation(tour, List.of("walk", "walk"), List.of()));
		assertFalse(constraint.validateBeforeEstimation(tour, List.of("pt", "walk"), List.of()));
		assertFalse(constraint.validateBeforeEstimation(tour, List.of("bicycle", "walk"), List.of()));

		assertEquals(1, factory.getBorderTourVehicleTrips());
		assertEquals(0, factory.getRelaxedVehicleTrips());
	}

	private static BorderTourVehicleConstraint.Factory createFactory() {
		return new BorderTourVehicleConstraint.Factory(RESTRICTED_MODES, planTrips -> HOME_ID);
	}

	private static Person person() {
		return PopulationUtils.getFactory().createPerson(Id.createPersonId("person"));
	}

	private static TourConstraint createConstraint(List<DiscreteModeChoiceTrip> trips) {
		return createFactory().createConstraint(person(), trips, ALL_MODES);
	}

	/**
	 * Builds the trips of a plan from a string of activity letters: H = home, O = outside, any other letter an
	 * inside activity at its own facility (the facility id is the letter, "home" for H).
	 */
	static List<DiscreteModeChoiceTrip> plan(String activities, String... modes) {
		if (modes.length != activities.length() - 1) {
			throw new IllegalArgumentException("Need one mode per trip");
		}

		List<Activity> sequence = new ArrayList<>();
		for (int index = 0; index < activities.length(); index++) {
			sequence.add(activity(activities.charAt(index), index));
		}

		List<DiscreteModeChoiceTrip> trips = new ArrayList<>();
		for (int index = 0; index < modes.length; index++) {
			trips.add(new DiscreteModeChoiceTrip(sequence.get(index), sequence.get(index + 1), modes[index], List.of(),
					0, index, index, new AttributesImpl()));
		}
		return trips;
	}

	private static Activity activity(char letter, int position) {
		String type = switch (letter) {
		case 'H' -> "home";
		case 'O' -> "outside";
		default -> "other";
		};
		Activity activity = PopulationUtils.createActivityFromCoord(type, new Coord(position, 0));
		activity.setFacilityId(letter == 'H' ? HOME_ID : Id.create(String.valueOf(letter), ActivityFacility.class));
		return activity;
	}
}
