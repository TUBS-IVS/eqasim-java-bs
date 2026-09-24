package org.eqasim.braunschweig.mode_choice.utilities.estimators;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.eqasim.braunschweig.fares.zonal.FareOutcomeCounter;
import org.eqasim.braunschweig.fares.zonal.FareQuote;
import org.eqasim.braunschweig.fares.zonal.FareQuoteSource;
import org.eqasim.braunschweig.fares.zonal.VrbFareConfigGroup;
import org.eqasim.braunschweig.fares.zonal.VrbFareModelTest;
import org.eqasim.braunschweig.mode_choice.parameters.BraunschweigModeParameters;
import org.junit.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.DefaultRoutedTripCandidate;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.DefaultTripCandidate;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;
import org.matsim.core.population.PopulationUtils;

/** Day-ticket cap over the DMC prefix; fixture prices from VrbFareModelTest.JSON (city single 360, city day 720). */
public class VrbZoneFarePtUtilityEstimatorTest {
	/** Quote source keyed by the leg mode "pt<index>" so each route in a prefix carries its own quote. */
	private static FareQuoteSource source(FareQuote... byIndex) {
		FareQuoteSource fares = mock(FareQuoteSource.class);
		org.mockito.stubbing.Answer<FareQuote> byLeg = invocation -> {
			List<?> elements = invocation.getArgument(1);
			int index = Integer.parseInt(((Leg) elements.get(0)).getMode().substring(2));
			return byIndex[index];
		};
		when(fares.quote(any(), any())).thenAnswer(byLeg);
		when(fares.quoteWithoutCounting(any(), any())).thenAnswer(byLeg);
		return fares;
	}

	@Test
	public void prefixTripsArePricedWithoutCountingSoEachQuoteCountsOnce() throws Exception {
		FareQuote city = new FareQuote(360, FareQuote.VRB_SINGLE_ADULT, "city");
		FareQuoteSource fares = source(city, city, city);
		estimator(fares, true).marginalCostEur(person(), route(2), List.of(
				new DefaultRoutedTripCandidate(0, "pt", route(0), 100), new DefaultRoutedTripCandidate(0, "pt", route(1), 100)));
		org.mockito.Mockito.verify(fares, org.mockito.Mockito.times(1)).quote(any(), any());
		org.mockito.Mockito.verify(fares, org.mockito.Mockito.times(2)).quoteWithoutCounting(any(), any());
	}

	private static List<PlanElement> route(int index) {
		return List.of(PopulationUtils.createLeg("pt" + index));
	}

	private static Person person() {
		return PopulationUtils.getFactory().createPerson(Id.createPersonId("p"));
	}

	private static VrbZoneFarePtUtilityEstimator estimator(FareQuoteSource fares, boolean cap) throws Exception {
		VrbFareConfigGroup config = new VrbFareConfigGroup();
		config.setEnabled("true");
		config.setDayTicketCapEnabled(Boolean.toString(cap));
		return new VrbZoneFarePtUtilityEstimator(BraunschweigModeParameters.buildDefault(), null, null, null, fares,
				VrbFareModelTest.model(), config, new FareOutcomeCounter());
	}

	@Test
	public void marginalCostIsCappedAtTheDayTicketOfTheHighestClass() throws Exception {
		FareQuote city = new FareQuote(360, FareQuote.VRB_SINGLE_ADULT, "city");
		VrbZoneFarePtUtilityEstimator estimator = estimator(source(city, city, city), true);
		TripCandidate first = new DefaultRoutedTripCandidate(0, "pt", route(0), 100);
		TripCandidate second = new DefaultRoutedTripCandidate(0, "pt", route(1), 100);
		// 1st trip 3.60; 2nd 3.60 (sum 7.20 = city day ticket); 3rd adds nothing.
		assertEquals(3.60, estimator.marginalCostEur(person(), route(0), List.of()), 1e-9);
		assertEquals(3.60, estimator.marginalCostEur(person(), route(1), List.of(first)), 1e-9);
		assertEquals(0.00, estimator.marginalCostEur(person(), route(2), List.of(first, second)), 1e-9);
	}

	@Test
	public void capUsesOnlyPtCandidatesInTheSuppliedPrefix() throws Exception {
		FareQuote city = new FareQuote(360, FareQuote.VRB_SINGLE_ADULT, "city");
		FareQuote ps2 = new FareQuote(560, FareQuote.VRB_SINGLE_ADULT, "ps2");
		VrbZoneFarePtUtilityEstimator estimator = estimator(source(city, ps2, city), true);
		TripCandidate carBetween = new DefaultTripCandidate(0, "car", 100);
		TripCandidate firstPt = new DefaultRoutedTripCandidate(0, "pt", route(0), 100);
		// pt 3.60 + car, current ps2 single 5.60: singles 9.20 < ps2 day ticket 11.20 -> full 5.60
		assertEquals(5.60, estimator.marginalCostEur(person(), route(1), List.of(firstPt, carBetween)), 1e-9);
		// without the pt candidate in the prefix the same trip costs the same: no leakage from absent branches
		assertEquals(5.60, estimator.marginalCostEur(person(), route(1), List.of(carBetween)), 1e-9);
	}

	@Test
	public void flatAndExternalOutcomesAreNeverCappedAndCapCanBeSwitchedOff() throws Exception {
		FareQuote city = new FareQuote(360, FareQuote.VRB_SINGLE_ADULT, "city");
		FareQuote external = new FareQuote(370, FareQuote.EXTERNAL_LOCAL_FLAT, null);
		TripCandidate first = new DefaultRoutedTripCandidate(0, "pt", route(0), 100);
		TripCandidate second = new DefaultRoutedTripCandidate(0, "pt", route(1), 100);
		assertEquals(3.70, estimator(source(city, city, external), true).marginalCostEur(person(), route(2),
				List.of(first, second)), 1e-9);
		assertEquals(3.60, estimator(source(city, city, city), false).marginalCostEur(person(), route(2),
				List.of(first, second)), 1e-9);
	}
}
