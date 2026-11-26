package org.eqasim.braunschweig.mode_choice.constraints;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
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
import org.matsim.facilities.ActivityFacility;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

/**
 * Pins the rule for same-facility leisure trips (eqasim-bs#679): a trip that starts and ends at the same facility and
 * has a leisure end may only use walk or bicycle; every other trip, and every trip with initial mode outside, is left
 * to the other constraints.
 */
public class SameFacilityLeisureTripConstraintTest {
	private static final String PREFIX = "same-facility leisure trips kept on foot or by bicycle: ";
	private static final List<String> ALL_MODES = List.of(TransportMode.car, BraunschweigModeChoiceModule.CAR_PASSENGER,
			TransportMode.pt, TransportMode.walk, BraunschweigModeChoiceModule.BICYCLE);

	@Test
	public void allowedModes_areWalkAndBicycle() {
		assertEquals(Set.of("walk", "bicycle"), SameFacilityLeisureTripConstraint.ALLOWED_MODES);
	}

	@Test
	public void loopLeg_onlyWalkOrBicycle() {
		DiscreteModeChoiceTrip trip = trip("home", "F", "leisure", "F", TransportMode.car);
		TripConstraint constraint = createConstraint(trip);

		assertFalse(constraint.validateBeforeEstimation(trip, TransportMode.car, List.of()));
		assertFalse(constraint.validateBeforeEstimation(trip, BraunschweigModeChoiceModule.CAR_PASSENGER, List.of()));
		assertFalse(constraint.validateBeforeEstimation(trip, TransportMode.pt, List.of()));
		assertTrue(constraint.validateBeforeEstimation(trip, TransportMode.walk, List.of()));
		assertTrue(constraint.validateBeforeEstimation(trip, BraunschweigModeChoiceModule.BICYCLE, List.of()));
	}

	@Test
	public void wayHomeAfterAStroll_onlyWalkOrBicycle() {
		DiscreteModeChoiceTrip trip = trip("leisure", "F", "home", "F", TransportMode.walk);
		TripConstraint constraint = createConstraint(trip);

		assertFalse(constraint.validateBeforeEstimation(trip, TransportMode.car, List.of()));
		assertTrue(constraint.validateBeforeEstimation(trip, TransportMode.walk, List.of()));
	}

	@Test
	public void bothEndsLeisure_noCar() {
		DiscreteModeChoiceTrip trip = trip("leisure", "F", "leisure", "F", TransportMode.walk);
		TripConstraint constraint = createConstraint(trip);

		assertFalse(constraint.validateBeforeEstimation(trip, TransportMode.car, List.of()));
		assertTrue(constraint.validateBeforeEstimation(trip, TransportMode.walk, List.of()));
	}

	@Test
	public void differentFacilitiesWithALeisureEnd_everyModeAllowed() {
		assertEveryModeAllowed(trip("home", "F", "leisure", "G", TransportMode.car));
		assertEveryModeAllowed(trip("leisure", "F", "home", "G", TransportMode.car));
	}

	@Test
	public void sameFacilityWithoutALeisureEnd_everyModeAllowed() {
		assertEveryModeAllowed(trip("home", "F", "home", "F", TransportMode.car));
		assertEveryModeAllowed(trip("work", "F", "work", "F", TransportMode.car));
	}

	@Test
	public void missingFacilityId_everyModeAllowed() {
		assertEveryModeAllowed(trip("home", null, "leisure", "F", TransportMode.car));
		assertEveryModeAllowed(trip("home", "F", "leisure", null, TransportMode.car));
		assertEveryModeAllowed(trip("leisure", null, "leisure", null, TransportMode.car));
	}

	@Test
	public void initialModeOutside_isLeftToTheOutsideConstraint() {
		DiscreteModeChoiceTrip trip = trip("home", "F", "leisure", "F", "outside");
		TripConstraint constraint = createConstraint(trip);

		assertTrue(constraint.validateBeforeEstimation(trip, "outside", List.of()));
		assertEveryModeAllowed(trip);
	}

	@Test
	public void factory_countsAppliedTripsAndRejectedCandidates() {
		DiscreteModeChoiceTrip loop = trip("home", "F", "leisure", "F", TransportMode.car, 0);
		DiscreteModeChoiceTrip wayHome = trip("leisure", "F", "home", "F", TransportMode.car, 1);
		DiscreteModeChoiceTrip other = trip("home", "F", "work", "W", TransportMode.car, 2);
		SameFacilityLeisureTripConstraint.Factory factory = new SameFacilityLeisureTripConstraint.Factory();
		TripConstraint constraint = factory.createConstraint(person(), List.of(loop, wayHome, other), ALL_MODES);

		assertEquals(3, factory.getTrips());
		assertEquals(2, factory.getAppliedTrips());
		assertEquals(0, factory.getRejectedCandidates());

		for (String mode : ALL_MODES) {
			constraint.validateBeforeEstimation(loop, mode, List.of());
			constraint.validateBeforeEstimation(other, mode, List.of());
		}

		// car, car_passenger and pt on the loop leg; nothing on the other trip.
		assertEquals(3, factory.getRejectedCandidates());
	}

	@Test
	public void runningSummary_logsAtPowersOfTwoOfTheAppliedTrips() {
		SameFacilityLeisureTripConstraint.Factory factory = new SameFacilityLeisureTripConstraint.Factory();

		List<String> messages = collectLog(() -> {
			for (int index = 0; index < 5; index++) {
				factory.createConstraint(person(), List.of(trip("home", "F", "leisure", "F", TransportMode.car)),
						ALL_MODES);
			}
		});

		// Lines at 1, 2 and 4 applied trips.
		assertEquals(List.of(
				PREFIX + "1 of 1 trips (100.00 %), 0 candidate modes rejected",
				PREFIX + "2 of 2 trips (100.00 %), 0 candidate modes rejected",
				PREFIX + "4 of 4 trips (100.00 %), 0 candidate modes rejected"),
				messages);
	}

	@Test
	public void finalSummary_logsTheRateAlsoWithoutAppliedTrips() {
		SameFacilityLeisureTripConstraint.Factory factory = new SameFacilityLeisureTripConstraint.Factory();
		factory.createConstraint(person(), List.of(trip("home", "F", "work", "W", TransportMode.car)), ALL_MODES);

		assertEquals(List.of(
				PREFIX + "0 of 1 trips (0.00 %), 0 candidate modes rejected"),
				collectLog(factory::logSummary));
	}

	@Test
	public void finalSummary_isSilentWhenTheFactoryWasNeverUsed() {
		assertEquals(List.of(), collectLog(new SameFacilityLeisureTripConstraint.Factory()::logSummary));
	}

	private static void assertEveryModeAllowed(DiscreteModeChoiceTrip trip) {
		TripConstraint constraint = createConstraint(trip);

		for (String mode : ALL_MODES) {
			assertTrue(mode, constraint.validateBeforeEstimation(trip, mode, List.of()));
		}
	}

	private static TripConstraint createConstraint(DiscreteModeChoiceTrip trip) {
		return new SameFacilityLeisureTripConstraint.Factory().createConstraint(person(), List.of(trip), ALL_MODES);
	}

	private static Person person() {
		return PopulationUtils.getFactory().createPerson(Id.createPersonId("person"));
	}

	private static DiscreteModeChoiceTrip trip(String originType, String originFacility, String destinationType,
			String destinationFacility, String initialMode) {
		return trip(originType, originFacility, destinationType, destinationFacility, initialMode, 0);
	}

	private static DiscreteModeChoiceTrip trip(String originType, String originFacility, String destinationType,
			String destinationFacility, String initialMode, int index) {
		return new DiscreteModeChoiceTrip(activity(originType, originFacility, 0),
				activity(destinationType, destinationFacility, 1), initialMode, List.of(), 0, index, index,
				new AttributesImpl());
	}

	private static Activity activity(String type, String facilityId, int position) {
		Activity activity = PopulationUtils.createActivityFromCoord(type, new Coord(position, 0));

		if (facilityId != null) {
			activity.setFacilityId(Id.create(facilityId, ActivityFacility.class));
		}

		return activity;
	}

	private static List<String> collectLog(Runnable action) {
		Logger logger = (Logger) LogManager.getLogger(SameFacilityLeisureTripConstraint.class);
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
			super("same-facility-leisure-test", null, PatternLayout.createDefaultLayout(), false, null);
		}

		@Override
		public void append(LogEvent event) {
			messages.add(event.getMessage().getFormattedMessage());
		}
	}
}
