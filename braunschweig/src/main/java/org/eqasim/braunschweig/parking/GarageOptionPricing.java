package org.eqasim.braunschweig.parking;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The price of a stay at ONE garage (spec Amendment E2 and the tariff structures E10, E11, E12 and E14 of parking cost zones
 * v2, eqasim-bs issue #436): a pure port of the Python reference functions {@code garage_metered_cents},
 * {@code garage_monthly_day_cents} and {@code garage_option_cents} of {@code braunschweig.parking.cost}, pinned to it by the
 * golden cases O01..O69. Money is integer euro cents and times are whole simulation seconds; no floating-point money is ever
 * formed (the bands of the Python reference are evaluated on {@code seconds / 60} floats there, which the Python tests prove
 * equal to the integer evaluation used here for every second near the band edges).
 *
 * <p>Tariff structures, by what the garage carries ({@link GarageTariff}):
 * <ul>
 * <li><b>Tiers</b> (spec E10, ASSUMPTION P6; checked first, also when the garage has a grace band): units of the one tier
 * unit length are counted from the arrival, or from the end of the first period where the arrival lies in the first
 * period's clock window; each started unit costs the price of the tier in force at the unit's start (start inclusive, end
 * exclusive, a tier may cross midnight), a start in no tier is free; the day cap applies once. A tiered garage with a grace
 * period (one closed free band, spec E14 amendment (1), ASSUMPTION P10) costs 0 for a stay not longer than the band
 * (elapsed time: the tiered form has no fee window), and a longer stay is priced by the tiers from its arrival as if there
 * were no free period.</li>
 * <li><b>Bands</b> (spec E11, ASSUMPTION P8): the chargeable seconds in the garage's fee window are priced by the band with
 * {@code from < d <= to} ({@code seconds <= 60 * to}); {@code free} costs 0, {@code total} its price, {@code increment} the
 * price reached at the band's start plus its price per started unit counted from the band's start,
 * {@code ceil((seconds - 60 * from) / (60 * unit))}. The price reached at the end of a band counts the started units of
 * the whole band, {@code ceil((to - from) / unit)}, never the floor. The day cap is applied as the smaller of schedule price
 * and cap. A stay longer than a closed schedule that ends at 1,440 min is priced per started 24 h (ASSUMPTION P9: full
 * periods at the price of 1,440 min plus the remainder, counted by
 * {@link GarageOptionCounters#countClosedScheduleRepeat()}); a closed schedule that ends elsewhere fails instead of guessing.
 * The free schedule {@code 0- free} of a car park that is free for every stay is the one open free band.</li>
 * <li><b>Single window</b>: the metering primitive of spec 3.2 rule 8
 * ({@link ParkingCostCalculator#meteredCents}, called, not copied) over the stay's seconds in the garage's fee window; the
 * first period is charged only when its clock window holds the arrival (ASSUMPTION P6 as amended).</li>
 * </ul>
 * A stay without a chargeable second costs 0. The monthly product (ASSUMPTION P2) is {@code monthly_cents / 21} rounded half
 * up, {@code (2 * monthly_cents + 21) / 42}, offered to work and education only; the option costs the cheaper of both.
 *
 * <p>Stateless and free of side effects apart from the optional counters; all methods are static.
 */
public final class GarageOptionPricing {
	/** ASSUMPTION P2: a monthly garage product is divided by this many working days. */
	static final int WORKING_DAYS_PER_MONTH = 21;

	private static final int MINUTES_PER_DAY = 24 * 60;
	private static final long SECONDS_PER_MINUTE = ParkingCostCalculator.SECONDS_PER_MINUTE;

	private GarageOptionPricing() {
	}

	/**
	 * The cost of the garage option of a stay (spec E2): the cheaper of the garage's metered or day product and, for the
	 * commuter purposes work and education only, its monthly product per working day (ASSUMPTION P2). 0 ct is a valid
	 * option. Times are floored to whole seconds like {@link ParkingCostCalculator#cents}.
	 *
	 * @param counters optional; counts the option evaluation and a repeated closed schedule
	 * @throws IllegalArgumentException for a non-finite or negative time or a departure before the arrival
	 */
	public static long optionCents(GarageTariff garage, double arrival_s, double departure_s, String purpose,
			GarageOptionCounters counters) {
		Objects.requireNonNull(garage, "garage");
		Objects.requireNonNull(purpose, "purpose");
		if (counters != null) {
			counters.countOptionEvaluation();
		}
		long cents = meteredCents(garage, arrival_s, departure_s, counters);
		if (garage.monthlyCents().isPresent() && ParkingCostCalculator.COMMUTER_PURPOSES.contains(purpose)) {
			cents = Math.min(cents, monthlyDayCents(garage.monthlyCents().getAsLong()));
		}
		return cents;
	}

	/**
	 * ASSUMPTION P2: a monthly garage product per working day, {@code monthly_cents / 21} rounded half up to the cent (integer
	 * arithmetic: {@code (2 * monthly_cents + 21) / 42}; the quotient of a whole number of cents by 21 is never an exact half).
	 */
	public static long monthlyDayCents(long monthlyCents) {
		return Math.addExact(Math.multiplyExact(2L, monthlyCents), WORKING_DAYS_PER_MONTH) / (2 * WORKING_DAYS_PER_MONTH);
	}

	/**
	 * The metered or day price of a stay at a garage in integer cents by the garage's tariff structure, without the monthly
	 * product; see the class comment.
	 *
	 * @param counters optional; counts a repeated closed schedule
	 * @throws IllegalArgumentException for a non-finite or negative time, a departure before the arrival, or a stay longer
	 *         than a closed schedule that does not end at 24 h
	 */
	public static long meteredCents(GarageTariff garage, double arrival_s, double departure_s, GarageOptionCounters counters) {
		long arrival = ParkingCostCalculator.wholeSeconds(arrival_s, "arrival_s");
		long departure = ParkingCostCalculator.wholeSeconds(departure_s, "departure_s");
		if (departure_s < arrival_s) {
			throw new IllegalArgumentException("departure_s " + departure_s + " is before arrival_s " + arrival_s
					+ "; a parking stay needs departure >= arrival");
		}
		if (garage.hasTiers()) {
			return tieredCents(garage, arrival, departure);
		}
		long chargeable_s = ParkingCostCalculator.chargeableSeconds(arrival_s, departure_s, garage.feeStart_s().getAsInt(),
				garage.feeEnd_s().getAsInt());
		if (garage.hasBands()) {
			return bandedCents(garage, chargeable_s, counters);
		}
		boolean firstPeriodApplies = firstPeriodApplies(garage, arrival);
		return ParkingCostCalculator.meteredCents(chargeable_s, garage.hourlyRateCents().getAsLong(),
				garage.billingUnitMinutes().getAsInt(), firstPeriodApplies ? garage.firstPeriodMinutes() : OptionalInt.empty(),
				firstPeriodApplies ? garage.firstPeriodCents() : OptionalLong.empty(), garage.dailyCapCents());
	}

	/**
	 * ASSUMPTION P6 as amended (spec E10): a first period tied to a clock window is charged only when the arrival lies in the
	 * window {@code [first_period_start_s, first_period_end_s)}; without a window it is charged for every use.
	 */
	private static boolean firstPeriodApplies(GarageTariff garage, long arrival_s) {
		if (garage.firstPeriodMinutes().isEmpty()) {
			return false;
		}
		if (garage.firstPeriodStart_s().isEmpty()) {
			return true;
		}
		long timeOfDay_s = Math.floorMod(arrival_s, (long) ParkingCostCalculator.SECONDS_PER_DAY);
		return garage.firstPeriodStart_s().getAsInt() <= timeOfDay_s && timeOfDay_s < garage.firstPeriodEnd_s().getAsInt();
	}

	private static long tieredCents(GarageTariff garage, long arrival_s, long departure_s) {
		if (departure_s == arrival_s) {
			return 0;
		}
		if (garage.hasBands()
				&& departure_s - arrival_s <= garage.bands().get(0).toMinutes().getAsInt() * SECONDS_PER_MINUTE) {
			return 0;
		}
		List<GarageTariff.Tier> tiers = garage.tiers();
		long cents = 0;
		long unitStart_s = arrival_s;
		if (firstPeriodApplies(garage, arrival_s)) {
			cents = garage.firstPeriodCents().getAsLong();
			unitStart_s = Math.addExact(arrival_s, garage.firstPeriodMinutes().getAsInt() * SECONDS_PER_MINUTE);
		}
		long unit_s = tiers.get(0).unitMinutes() * SECONDS_PER_MINUTE;
		OptionalLong cap = garage.dailyCapCents();
		while (unitStart_s < departure_s) {
			long timeOfDay_s = Math.floorMod(unitStart_s, (long) ParkingCostCalculator.SECONDS_PER_DAY);
			for (GarageTariff.Tier tier : tiers) {
				if (tier.covers(timeOfDay_s)) {
					cents = Math.addExact(cents, tier.priceCents());
					break;
				}
			}
			if (cap.isPresent() && cents >= cap.getAsLong()) {
				return cap.getAsLong();
			}
			unitStart_s = Math.addExact(unitStart_s, unit_s);
		}
		return cents;
	}

	private static long bandedCents(GarageTariff garage, long chargeable_s, GarageOptionCounters counters) {
		if (chargeable_s == 0) {
			return 0;
		}
		List<GarageTariff.Band> bands = garage.bands();
		GarageTariff.Band last = bands.get(bands.size() - 1);
		if (last.toMinutes().isEmpty() || chargeable_s <= last.toMinutes().getAsInt() * SECONDS_PER_MINUTE) {
			return bandPriceCents(garage, chargeable_s);
		}
		// ASSUMPTION P9: a stay longer than a closed schedule (Peine ends at 1440 min) is priced per started 24 h: k full
		// periods at the price of 1440 min plus the price of the remainder, every period capped by the day cap. Counted,
		// never a silent guess; a closed schedule that does not end at 24 h states no rule for a longer stay and fails.
		if (last.toMinutes().getAsInt() != MINUTES_PER_DAY) {
			throw new IllegalArgumentException("garage " + garage.garageId() + ": the stay of " + chargeable_s
					+ " s is longer than the closed schedule that ends at " + last.toMinutes().getAsInt()
					+ " min, and P9 repeats only a schedule that ends at " + MINUTES_PER_DAY + " min");
		}
		if (counters != null) {
			counters.countClosedScheduleRepeat();
		}
		long fullPeriods = (chargeable_s - 1) / ParkingCostCalculator.SECONDS_PER_DAY;
		long remainder_s = chargeable_s - fullPeriods * ParkingCostCalculator.SECONDS_PER_DAY;
		return Math.addExact(Math.multiplyExact(fullPeriods, bandPriceCents(garage, ParkingCostCalculator.SECONDS_PER_DAY)),
				bandPriceCents(garage, remainder_s));
	}

	/**
	 * ASSUMPTION P8, the integer form of {@code duration_band_price_eur} of the Python reference: the price of a stay of
	 * {@code seconds} (above 0, within the schedule) with the day cap as the smaller of schedule price and cap.
	 */
	private static long bandPriceCents(GarageTariff garage, long seconds) {
		long cents = 0;
		long reachedCents = 0;
		boolean priced = false;
		for (GarageTariff.Band band : garage.bands()) {
			if (band.toMinutes().isEmpty() || seconds <= band.toMinutes().getAsInt() * SECONDS_PER_MINUTE) {
				cents = priceInBand(band, seconds, reachedCents);
				priced = true;
				break;
			}
			reachedCents = priceAtBandEnd(band, reachedCents);
		}
		if (!priced) {
			throw new IllegalArgumentException("garage " + garage.garageId() + ": the duration " + seconds
					+ " s is beyond the last band: the schedule states no price for a longer stay");
		}
		if (garage.dailyCapCents().isPresent()) {
			cents = Math.min(cents, garage.dailyCapCents().getAsLong());
		}
		return cents;
	}

	/** The price of a stay of {@code seconds} inside {@code band}, whose price at its start is {@code reachedCents}. */
	private static long priceInBand(GarageTariff.Band band, long seconds, long reachedCents) {
		return switch (band.kind()) {
		case FREE -> 0;
		case TOTAL -> band.priceCents();
		case INCREMENT -> {
			long startedUnits = Math.ceilDiv(seconds - band.fromMinutes() * SECONDS_PER_MINUTE,
					band.unitMinutes().getAsInt() * SECONDS_PER_MINUTE);
			yield Math.addExact(reachedCents, Math.multiplyExact(band.priceCents(), startedUnits));
		}
		};
	}

	/** The price reached at the end of {@code band}: started units of the whole band, {@code ceil((to - from) / unit)}. */
	private static long priceAtBandEnd(GarageTariff.Band band, long reachedCents) {
		return switch (band.kind()) {
		case FREE -> 0;
		case TOTAL -> band.priceCents();
		case INCREMENT -> {
			long startedUnits = Math.ceilDiv(band.toMinutes().getAsInt() - band.fromMinutes(), band.unitMinutes().getAsInt());
			yield Math.addExact(reachedCents, Math.multiplyExact(band.priceCents(), startedUnits));
		}
		};
	}
}
