package org.eqasim.braunschweig.fares.zonal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/** Cheapest day cash with the fixture prices of VrbFareModelTest (city 360/720, ps3 770/1540, ps4 1230/2460). */
public class DayTicketCapTest {
	private static final List<String> CLASSES = List.of("city", "ps1", "ps2", "ps3", "ps4");

	private static FareQuote single(VrbFareModel model, String priceClass) {
		return new FareQuote(model.singleCents(priceClass, false), FareQuote.VRB_SINGLE_ADULT, priceClass);
	}

	@Test
	public void ps3AfterThreeCitySinglesCostsItsOwnSingleNotMore() throws Exception {
		VrbFareModel model = VrbFareModelTest.model();
		FareQuote city = single(model, "city");
		// city day ticket 720 + ps3 single 770 = 1490 beats the ps3 day ticket 1540 and all singles 1850.
		assertEquals(770, DayTicketCap.marginalCents(model, List.of(city, city, city), single(model, "ps3")));
	}

	@Test
	public void fourthCityTripAfterPs4AndTwoCityTripsIsFree() throws Exception {
		VrbFareModel model = VrbFareModelTest.model();
		FareQuote city = single(model, "city");
		// ps4 single 1230 + city day ticket 720 = 1950 already covers every further city trip.
		assertEquals(0, DayTicketCap.marginalCents(model, List.of(single(model, "ps4"), city, city), city));
	}

	@Test
	public void cheapestCashMatchesSubsetEnumerationAndMarginalStaysWithinZeroAndTheSingle() throws Exception {
		VrbFareModel model = VrbFareModelTest.model();
		for (List<String> day : sequences(5)) {
			List<FareQuote> quotes = new ArrayList<>();
			for (String priceClass : day) {
				quotes.add(single(model, priceClass));
			}
			assertEquals(day.toString(), subsetOptimum(model, quotes), DayTicketCap.cheapestCashCents(model, quotes));
			FareQuote last = quotes.get(quotes.size() - 1);
			long marginal = DayTicketCap.marginalCents(model, quotes.subList(0, quotes.size() - 1), last);
			assertTrue(day + " marginal " + marginal, marginal >= 0 && marginal <= last.cents());
		}
	}

	/** Independent oracle: the trips covered by a day ticket form a subset, which needs the day ticket of its highest class. */
	private static long subsetOptimum(VrbFareModel model, List<FareQuote> quotes) {
		long best = Long.MAX_VALUE;
		for (int mask = 0; mask < (1 << quotes.size()); mask++) {
			long cost = 0;
			int highest = -1;
			for (int index = 0; index < quotes.size(); index++) {
				if ((mask & (1 << index)) != 0) {
					highest = Math.max(highest, CLASSES.indexOf(quotes.get(index).priceClass()));
				} else {
					cost += quotes.get(index).cents();
				}
			}
			if (highest >= 0) {
				cost += model.dayTicketCents(CLASSES.get(highest));
			}
			best = Math.min(best, cost);
		}
		return best;
	}

	private static List<List<String>> sequences(int maximumLength) {
		List<List<String>> result = new ArrayList<>();
		List<List<String>> frontier = List.of(List.of());
		for (int length = 1; length <= maximumLength; length++) {
			List<List<String>> next = new ArrayList<>();
			for (List<String> prefix : frontier) {
				for (String priceClass : CLASSES) {
					List<String> sequence = new ArrayList<>(prefix);
					sequence.add(priceClass);
					next.add(sequence);
				}
			}
			result.addAll(next);
			frontier = next;
		}
		return result;
	}
}
