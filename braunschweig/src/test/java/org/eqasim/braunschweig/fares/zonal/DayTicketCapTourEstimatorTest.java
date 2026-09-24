package org.eqasim.braunschweig.fares.zonal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.DefaultTourCandidate;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourCandidate;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripEstimator;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.DefaultRoutedTripCandidate;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.RoutedTripCandidate;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.timing.TimeInterpretation;

/** Prefix handling carried over from the parked branch's RegionalFareDmcSeamTest, plus the cap correction. */
public class DayTicketCapTourEstimatorTest {
	private static final DayTicketCapAdjustment NO_CAP = (person, trip, elements, previous) -> 0.0;

	private static DayTicketCapTourEstimator estimator(TripEstimator delegate, DayTicketCapAdjustment cap) {
		return new DayTicketCapTourEstimator(delegate, TimeInterpretation.create(ConfigUtils.createConfig()), cap);
	}

	private static DiscreteModeChoiceTrip trip(double departureTime) {
		DiscreteModeChoiceTrip trip = mock(DiscreteModeChoiceTrip.class);
		Activity origin = PopulationUtils.createActivityFromCoord("home", new Coord(0, 0));
		origin.setEndTime(departureTime);
		when(trip.getDepartureTime()).thenReturn(departureTime);
		when(trip.getOriginActivity()).thenReturn(origin);
		return trip;
	}

	private static TripCandidate candidate(String mode, double utility) {
		TripCandidate candidate = mock(TripCandidate.class);
		when(candidate.getMode()).thenReturn(mode);
		when(candidate.getUtility()).thenReturn(utility);
		when(candidate.getDuration()).thenReturn(0.0);
		return candidate;
	}

	private static DefaultRoutedTripCandidate routedPt(double utility) {
		List<PlanElement> elements = List.of(PopulationUtils.createLeg("pt"));
		return new DefaultRoutedTripCandidate(utility, "pt", elements, 0.0);
	}

	@Test
	public void forwardsEarlierSelectedToursAndLeavesBranchesSeparate() {
		Person person = mock(Person.class);
		TripCandidate earlierPt = candidate("pt", 2.0);
		TripCandidate earlierCar = candidate("car", 1.0);
		TourCandidate committed = new DefaultTourCandidate(3.0, List.of(earlierPt, earlierCar));
		TripCandidate current = candidate("pt", 4.0);
		AtomicReference<List<TripCandidate>> seen = new AtomicReference<>();
		TripEstimator delegate = (p, mode, t, previous) -> {
			seen.set(List.copyOf(previous));
			return current;
		};
		DayTicketCapTourEstimator estimator = estimator(delegate, NO_CAP);

		TourCandidate first = estimator.estimateTour(person, List.of("pt"), List.of(trip(100.0)), List.of(committed));
		assertEquals(List.of(earlierPt, earlierCar), seen.get());
		assertEquals(List.of(current), first.getTripCandidates());
		assertEquals(4.0, first.getUtility(), 0.0);
		estimator.estimateTour(person, List.of("pt"), List.of(trip(100.0)), List.of());
		assertEquals(List.of(), seen.get());
		assertEquals(List.of(earlierPt, earlierCar), committed.getTripCandidates());
	}

	@Test
	public void capCorrectionIsAddedToRoutedPtCandidatesAndKeepsTheirRoute() {
		DefaultRoutedTripCandidate pt = routedPt(4.0);
		TripCandidate car = candidate("car", 1.0);
		TripEstimator delegate = (p, mode, t, previous) -> "pt".equals(mode) ? (TripCandidate) pt : car;
		List<String> adjustedModes = new ArrayList<>();
		DayTicketCapAdjustment cap = (person, trip, elements, previous) -> {
			adjustedModes.add("pt");
			assertSame(pt.getRoutedPlanElements(), elements);
			return 1.5;
		};

		TourCandidate tour = estimator(delegate, cap).estimateTour(mock(Person.class), List.of("pt", "car"),
				List.of(trip(100.0), trip(200.0)), List.of());
		assertEquals(6.5, tour.getUtility(), 1e-12);
		assertEquals(List.of("pt"), adjustedModes);
		TripCandidate adjusted = tour.getTripCandidates().get(0);
		assertEquals(5.5, adjusted.getUtility(), 1e-12);
		assertSame(pt.getRoutedPlanElements(), ((RoutedTripCandidate) adjusted).getRoutedPlanElements());
		assertSame(car, tour.getTripCandidates().get(1));
	}

	@Test
	public void laterTripOfATourSeesEarlierToursAndTheTripsBeforeIt() {
		TripCandidate earlierPt = candidate("pt", 2.0);
		TourCandidate committed = new DefaultTourCandidate(2.0, List.of(earlierPt));
		DefaultRoutedTripCandidate outbound = routedPt(3.0);
		DefaultRoutedTripCandidate inbound = routedPt(3.0);
		List<List<TripCandidate>> delegatePrefixes = new ArrayList<>();
		TripEstimator delegate = (p, mode, t, previous) -> {
			delegatePrefixes.add(List.copyOf(previous));
			return (TripCandidate) (delegatePrefixes.size() == 1 ? outbound : inbound);
		};
		List<List<TripCandidate>> capPrefixes = new ArrayList<>();
		DayTicketCapAdjustment cap = (person, trip, elements, previous) -> {
			capPrefixes.add(List.copyOf(previous));
			return capPrefixes.size() == 2 ? -0.5 : 0.0;
		};

		TourCandidate tour = estimator(delegate, cap).estimateTour(mock(Person.class), List.of("pt", "pt"),
				List.of(trip(100.0), trip(200.0)), List.of(committed));
		assertEquals(List.of(earlierPt), capPrefixes.get(0));
		assertEquals(List.of(earlierPt, outbound), capPrefixes.get(1));
		assertEquals(List.of(earlierPt, outbound), delegatePrefixes.get(1));
		assertEquals(5.5, tour.getUtility(), 1e-12);
	}
}
