package org.eqasim.braunschweig.fares.zonal;

import java.util.ArrayList;
import java.util.List;

/**
 * Cheapest VRB cash of one person's day (ADR-0133 D9). The traveller either buys only singles, or one
 * day ticket of a price class k plus singles for the trips whose price class ranks above k; the day
 * cash is the minimum over these options. A second day ticket never helps: the lower one covers only
 * trips the higher one covers too. The marginal cash of a trip is therefore between zero and its own
 * single price. ASSUMPTION (D9): a day ticket of class k covers every trip of class k or lower,
 * wherever it runs in the VRB; ticket validity windows are not modelled.
 *
 * <p>All amounts are euro cents. Only VRB cash quotes (short trip and singles, {@link FareQuote#isVrbCash()})
 * belong in the lists; the caller filters them.
 */
public final class DayTicketCap {
	private DayTicketCap() {
	}

	/** Cheapest cash in euro cents for the given VRB cash trips of one day. */
	public static long cheapestCashCents(VrbFareModel model, List<FareQuote> vrbCashTrips) {
		long best = 0;
		for (FareQuote trip : vrbCashTrips) {
			best += trip.cents();
		}
		for (String dayClass : model.priceClasses()) {
			long cost = model.dayTicketCents(dayClass);
			for (FareQuote trip : vrbCashTrips) {
				if (!model.covers(dayClass, trip.priceClass())) {
					cost += trip.cents();
				}
			}
			best = Math.min(best, cost);
		}
		return best;
	}

	/** Increase of the cheapest day cash in euro cents when {@code current} is added to {@code before}. */
	public static long marginalCents(VrbFareModel model, List<FareQuote> before, FareQuote current) {
		List<FareQuote> after = new ArrayList<>(before);
		after.add(current);
		return cheapestCashCents(model, after) - cheapestCashCents(model, before);
	}
}
