package org.eqasim.braunschweig.mode_choice.constraints;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.components.constraints.VehicleTourConstraint;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourCandidate;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourConstraint;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripConstraint;
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

	@Test
	public void validateAfterEstimation_delegatesOutsideBorderToursOnly() {
		List<DiscreteModeChoiceTrip> trips = plan("HWSHAOH", "car", "car", "car", "walk", "walk", "walk");
		boolean[] flags = BorderTourVehicleConstraint.borderTourFlags(trips);
		RejectingDelegate delegate = new RejectingDelegate();
		BorderTourVehicleConstraint constraint = new BorderTourVehicleConstraint(RESTRICTED_MODES, flags,
				new boolean[trips.size()], delegate);

		// Ordinary tour (trips 0-2): the delegate's answer.
		assertFalse(constraint.validateAfterEstimation(trips.subList(0, 3), null, List.of()));
		assertEquals(1, delegate.afterEstimationCalls);

		// Border tour (trips 3-5): always true, the delegate is not asked.
		assertTrue(constraint.validateAfterEstimation(trips.subList(3, 6), null, List.of()));
		assertEquals(1, delegate.afterEstimationCalls);
	}

	@Test
	public void finalSummary_logsTheRateAlsoWithoutRelaxedLegs() {
		BorderTourVehicleConstraint.Factory factory = createFactory();
		factory.createConstraint(person(), plan("HABOH", "car", "walk", "pt", "pt"), ALL_MODES);

		List<String> messages = collectLog(factory::logSummary);

		assertEquals(List.of("border-tour vehicle legs relaxed for missing availability: 0 of 1 (0.00 %)"), messages);
	}

	@Test
	public void finalSummary_isSilentWithoutBorderTourVehicleLegs() {
		BorderTourVehicleConstraint.Factory factory = createFactory();
		factory.createConstraint(person(), plan("HWSH", "car", "car", "car"), ALL_MODES);

		assertEquals(List.of(), collectLog(factory::logSummary));
	}

	@Test
	public void personWarnings_stopAfterTen() {
		BorderTourVehicleConstraint.Factory factory = createFactory();
		List<String> withoutCar = List.of("bicycle", "pt", "walk", "car_passenger");

		List<String> messages = collectLog(() -> {
			for (int index = 0; index < 12; index++) {
				factory.createConstraint(person(), plan("HABOH", "car", "walk", "pt", "pt"), withoutCar);
			}
		});

		assertEquals(10, messages.stream().filter(message -> message.startsWith("Person ")).count());
		assertEquals(12, factory.getRelaxedVehicleTrips());
		// Running summary lines at 1, 2, 4 and 8 relaxed legs.
		assertEquals(4, messages.stream().filter(message -> message.startsWith("border-tour vehicle legs")).count());
	}

	@Test
	public void withSameFacilityLeisureRule_aCaughtCarTripIsLeftToThatRule() {
		// Trip 1 (A -> a) is a same-facility leisure trip with initial mode car inside a border tour.
		List<DiscreteModeChoiceTrip> trips = plan("HAaBOH", "car", "car", "car", "pt", "pt");
		BorderTourVehicleConstraint.Factory factory = new BorderTourVehicleConstraint.Factory(RESTRICTED_MODES,
				planTrips -> HOME_ID, true);
		TourConstraint border = factory.createConstraint(person(), trips, ALL_MODES);
		TripConstraint sameFacility = new SameFacilityLeisureTripConstraint.Factory().createConstraint(person(), trips,
				ALL_MODES);
		List<DiscreteModeChoiceTrip> tour = trips.subList(0, 3);

		// Walk on the caught trip, the other car legs pinned: valid under both rules.
		assertTrue(border.validateBeforeEstimation(tour, List.of("car", "walk", "car"), List.of()));
		assertTrue(bothRules(border, sameFacility, tour, List.of("car", "walk", "car")));

		// A bicycle the person does not use there: refused as a new vehicle leg.
		assertFalse(border.validateBeforeEstimation(tour, List.of("car", "bicycle", "car"), List.of()));
		assertFalse(bothRules(border, sameFacility, tour, List.of("car", "bicycle", "car")));

		// Car kept on the caught trip: the border rule no longer decides it, the same-facility rule rejects it.
		assertTrue(border.validateBeforeEstimation(tour, List.of("car", "car", "car"), List.of()));
		assertFalse(bothRules(border, sameFacility, tour, List.of("car", "car", "car")));

		// The other vehicle legs stay pinned.
		assertFalse(border.validateBeforeEstimation(tour, List.of("walk", "walk", "car"), List.of()));
		assertFalse(border.validateBeforeEstimation(tour, List.of("car", "walk", "pt"), List.of()));

		// The caught trip is not a vehicle leg of the border rule.
		assertEquals(2, factory.getBorderTourVehicleTrips());
		assertTrue(factory.isSameFacilityLeisureExemption());
	}

	@Test
	public void withSameFacilityLeisureRule_aCaughtBicycleTripKeepsTheBicycleOrWalks() {
		List<DiscreteModeChoiceTrip> trips = plan("HAaBOH", "car", "bicycle", "car", "pt", "pt");
		BorderTourVehicleConstraint.Factory factory = new BorderTourVehicleConstraint.Factory(RESTRICTED_MODES,
				planTrips -> HOME_ID, true);
		TourConstraint border = factory.createConstraint(person(), trips, ALL_MODES);
		TripConstraint sameFacility = new SameFacilityLeisureTripConstraint.Factory().createConstraint(person(), trips,
				ALL_MODES);
		List<DiscreteModeChoiceTrip> tour = trips.subList(0, 3);

		assertTrue(bothRules(border, sameFacility, tour, List.of("car", "bicycle", "car")));
		assertTrue(bothRules(border, sameFacility, tour, List.of("car", "walk", "car")));
		assertFalse(border.validateBeforeEstimation(tour, List.of("car", "car", "car"), List.of()));
		assertFalse(bothRules(border, sameFacility, tour, List.of("car", "car", "car")));
		assertEquals(2, factory.getBorderTourVehicleTrips());
	}

	@Test
	public void withoutSameFacilityLeisureRule_theCaughtTripStaysAPinnedVehicleLeg() {
		List<DiscreteModeChoiceTrip> trips = plan("HAaBOH", "car", "car", "car", "pt", "pt");
		BorderTourVehicleConstraint.Factory factory = createFactory();
		TourConstraint border = factory.createConstraint(person(), trips, ALL_MODES);
		List<DiscreteModeChoiceTrip> tour = trips.subList(0, 3);

		assertFalse(border.validateBeforeEstimation(tour, List.of("car", "walk", "car"), List.of()));
		assertTrue(border.validateBeforeEstimation(tour, List.of("car", "car", "car"), List.of()));
		assertEquals(3, factory.getBorderTourVehicleTrips());
		assertFalse(factory.isSameFacilityLeisureExemption());
	}

	/** Both rules as the tour-based model combines them: the tour rule and the trip rule on every trip. */
	private static boolean bothRules(TourConstraint border, TripConstraint sameFacility,
			List<DiscreteModeChoiceTrip> tour, List<String> modes) {
		if (!border.validateBeforeEstimation(tour, modes, List.of())) {
			return false;
		}

		for (int index = 0; index < tour.size(); index++) {
			if (!sameFacility.validateBeforeEstimation(tour.get(index), modes.get(index), List.of())) {
				return false;
			}
		}

		return true;
	}

	private static List<String> collectLog(Runnable action) {
		Logger logger = (Logger) LogManager.getLogger(BorderTourVehicleConstraint.class);
		CollectingAppender appender = new CollectingAppender();
		Level level = logger.getLevel();
		logger.addAppender(appender);
		logger.setLevel(Level.INFO);
		appender.start();

		try {
			action.run();
			return appender.messages;
		} finally {
			logger.removeAppender(appender);
			logger.setLevel(level);
			appender.stop();
		}
	}

	private static class CollectingAppender extends AbstractAppender {
		private final List<String> messages = new ArrayList<>();

		CollectingAppender() {
			super("border-tour-vehicle-test", null, PatternLayout.createDefaultLayout(), false, null);
		}

		@Override
		public void append(LogEvent event) {
			messages.add(event.getMessage().getFormattedMessage());
		}
	}

	/** Rejects every candidate after estimation and counts the calls. */
	private static class RejectingDelegate implements TourConstraint {
		int afterEstimationCalls = 0;

		@Override
		public boolean validateBeforeEstimation(List<DiscreteModeChoiceTrip> tour, List<String> modes,
				List<List<String>> previousModes) {
			return true;
		}

		@Override
		public boolean validateAfterEstimation(List<DiscreteModeChoiceTrip> tour, TourCandidate candidate,
				List<TourCandidate> previousCandidates) {
			afterEstimationCalls++;
			return false;
		}
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
	 * Builds the trips of a plan from a string of activity letters: H = home, O = outside, any other upper-case letter
	 * an inside activity at its own facility (the facility id is the letter, "home" for H), a lower-case letter a
	 * leisure activity at the facility of its upper-case letter.
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
		default -> Character.isLowerCase(letter) ? "leisure" : "other";
		};
		Activity activity = PopulationUtils.createActivityFromCoord(type, new Coord(position, 0));
		String facility = String.valueOf(Character.toUpperCase(letter));
		activity.setFacilityId(letter == 'H' ? HOME_ID : Id.create(facility, ActivityFacility.class));
		return activity;
	}
}
