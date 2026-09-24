package org.eqasim.braunschweig.fares.zonal;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.DefaultTourCandidate;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourCandidate;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripEstimator;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.utils.timing.TimeInterpretation;

/** Carried over from the parked branch's RegionalFareDmcSeamTest (same assertions). */
public class PrefixAwareTourEstimatorTest {
	@Test
	public void forwardsEarlierSelectedToursAndLeavesBranchesSeparate() {
		Config config = ConfigUtils.createConfig();
		Person person = mock(Person.class);
		DiscreteModeChoiceTrip trip = mock(DiscreteModeChoiceTrip.class);
		when(trip.getDepartureTime()).thenReturn(100.0);
		TripCandidate earlierPt = candidate("pt", 2.0);
		TripCandidate earlierCar = candidate("car", 1.0);
		TourCandidate committed = new DefaultTourCandidate(3.0, List.of(earlierPt, earlierCar));
		TripCandidate current = candidate("pt", 4.0);
		AtomicReference<List<TripCandidate>> seen = new AtomicReference<>();
		TripEstimator delegate = (p, mode, t, previous) -> {
			seen.set(List.copyOf(previous));
			return current;
		};
		PrefixAwareTourEstimator estimator = new PrefixAwareTourEstimator(delegate, TimeInterpretation.create(config));

		TourCandidate first = estimator.estimateTour(person, List.of("pt"), List.of(trip), List.of(committed));
		assertEquals(List.of(earlierPt, earlierCar), seen.get());
		assertEquals(List.of(current), first.getTripCandidates());
		assertEquals(4.0, first.getUtility(), 0.0);
		estimator.estimateTour(person, List.of("pt"), List.of(trip), List.of());
		assertEquals(List.of(), seen.get());
		assertEquals(List.of(earlierPt, earlierCar), committed.getTripCandidates());
	}

	private static TripCandidate candidate(String mode, double utility) {
		TripCandidate candidate = mock(TripCandidate.class);
		when(candidate.getMode()).thenReturn(mode);
		when(candidate.getUtility()).thenReturn(utility);
		when(candidate.getDuration()).thenReturn(0.0);
		return candidate;
	}
}
