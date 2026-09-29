package org.eqasim.braunschweig.parking;

import java.util.Objects;
import java.util.Set;

/**
 * Parking cost of one car stay in one zone: a pure port of design "parking cost zones", section 3.2 (eqasim-bs issue
 * #436). The Python reference braunschweig.parking.cost implements the same rules; the 26 golden cases of
 * {@code parking_golden_cases.json} pin both implementations to identical cents and outcomes.
 *
 * <p>Units: money in integer euro cents, times in whole simulation seconds (seconds after midnight of the first
 * simulated day, possibly beyond 24 h), tariff durations in minutes. MATSim passes times as doubles; they are floored to
 * whole seconds before any arithmetic, so sub-second noise never starts an extra minute or billing unit. The arithmetic
 * is integer and overflow-checked ({@code Math.*Exact}); no floating-point money is ever formed.
 *
 * <p>Assumptions (labels of the design's assumptions register): D1 the simulated day is an average weekday and the fee
 * window repeats every 86,400 s; T1 a car at the last activity of the plan pays until the fee window of its arrival day
 * ends ({@link #terminalDeparture_s}); M1 the maximum stay is compared with the chargeable duration, because the
 * Braunschweig ordinance restricts the stay during fee hours only, and other municipalities are treated the same.
 *
 * <p>Stateless and free of side effects; all methods are static.
 */
public final class ParkingCostCalculator {
	/** Length of the simulated day; the fee window of a zone repeats with this period (assumption D1). */
	public static final int SECONDS_PER_DAY = 86_400;

	/** The only implemented terminal-stay rule (assumption T1), named by the tariff model and the config module. */
	public static final String TERMINAL_STAY_RULE_UNTIL_FEE_END = "until_fee_end";

	/** Activity purpose whose stays never pay: the car stands at the person's home. */
	public static final String HOME_PURPOSE = "home";

	/** Purposes that buy the campus member day product; every other purpose buys the guest day product. */
	public static final Set<String> CAMPUS_MEMBER_PURPOSES = Set.of("work", "education");

	private static final int SECONDS_PER_MINUTE = 60;
	private static final int MINUTES_PER_HOUR = 60;

	/** 2^63: doubles at or above it cannot be floored to an exact long number of seconds. */
	private static final double LONG_SECONDS_LIMIT = 0x1p63;

	/**
	 * Cost of one stay.
	 *
	 * @param cents non-negative price in euro cents
	 * @param outcome the rule that decided the price
	 */
	public record Result(long cents, ParkingOutcome outcome) {
		public Result {
			if (cents < 0) {
				throw new IllegalArgumentException("parking cost must be non-negative euro cents, got " + cents);
			}
			Objects.requireNonNull(outcome, "outcome");
		}
	}

	private ParkingCostCalculator() {
	}

	/**
	 * Seconds of the stay [arrival, departure) inside the daily fee window [feeStart_s, feeEnd_s), summed over every
	 * simulated day the stay touches (section 3.2): the sum over k = floor(a / 86400) .. floor((d - 1) / 86400) of
	 * {@code max(0, min(d, k * 86400 + feeEnd_s) - max(a, k * 86400 + feeStart_s))}, with a and d floored to whole seconds.
	 *
	 * <p>Evaluated in closed form: every day strictly between the first and the last starts after the arrival and ends
	 * before the departure, so it contributes its whole window {@code feeEnd_s - feeStart_s}. The result equals the
	 * day-by-day sum, and the evaluation takes constant time whatever the length of the stay.
	 *
	 * @throws IllegalArgumentException for a non-finite or negative time, a departure before the arrival, or a fee window
	 *         outside {@code 0 <= feeStart_s < feeEnd_s <= 86400}
	 */
	public static long chargeableSeconds(double arrival_s, double departure_s, int feeStart_s, int feeEnd_s) {
		requireFeeWindow(feeStart_s, feeEnd_s, "chargeableSeconds");
		long arrival = wholeSeconds(arrival_s, "arrival_s");
		long departure = wholeSeconds(departure_s, "departure_s");
		if (departure_s < arrival_s) {
			throw new IllegalArgumentException("departure_s " + departure_s + " is before arrival_s " + arrival_s
					+ "; a parking stay needs departure >= arrival");
		}
		long firstDay = Math.floorDiv(arrival, SECONDS_PER_DAY);
		long lastDay = Math.floorDiv(departure - 1, SECONDS_PER_DAY);
		if (lastDay < firstDay) {
			// Empty day range of section 3.2: a zero-length stay exactly at midnight.
			return 0;
		}
		long chargeable_s = feeWindowOverlap_s(arrival, departure, firstDay, feeStart_s, feeEnd_s);
		if (lastDay > firstDay) {
			chargeable_s = Math.addExact(chargeable_s, feeWindowOverlap_s(arrival, departure, lastDay, feeStart_s, feeEnd_s));
			long interiorDays = lastDay - firstDay - 1;
			chargeable_s = Math.addExact(chargeable_s, Math.multiplyExact(interiorDays, (long) (feeEnd_s - feeStart_s)));
		}
		return chargeable_s;
	}

	/**
	 * Departure of a car parked at the last activity of the plan (assumption T1, rule until_fee_end): the end of the fee
	 * window of the arrival day, or the arrival itself when the car arrives after that end,
	 * {@code d = max(a, floor(a / 86400) * 86400 + feeEnd_s)}. The day is taken from the floored arrival; the result is
	 * never before the unfloored arrival, so it is always a valid departure for {@link #cents}.
	 *
	 * @throws IllegalArgumentException for a non-finite or negative arrival, or feeEnd_s outside (0, 86400]
	 */
	public static double terminalDeparture_s(double arrival_s, int feeEnd_s) {
		if (feeEnd_s <= 0 || feeEnd_s > SECONDS_PER_DAY) {
			throw new IllegalArgumentException("fee_end_s must be within (0, " + SECONDS_PER_DAY + "], got " + feeEnd_s);
		}
		long arrival = wholeSeconds(arrival_s, "arrival_s");
		long arrivalDayStart_s = Math.multiplyExact(Math.floorDiv(arrival, SECONDS_PER_DAY), SECONDS_PER_DAY);
		long feeEndOfArrivalDay_s = Math.addExact(arrivalDayStart_s, feeEnd_s);
		return Math.max(arrival_s, (double) feeEndOfArrivalDay_s);
	}

	/**
	 * Price and deciding rule of one stay in the zone of {@code tariff}; the rules of section 3.2 in this order:
	 * <ol>
	 * <li>purpose {@code home}: 0, HOME;</li>
	 * <li>{@code parkingFree}: 0, EMPLOYER_FREE;</li>
	 * <li>resident-exempt zone and resident of this zone: 0, RESIDENT_FREE;</li>
	 * <li>no chargeable second: 0, OUTSIDE_FEE_HOURS;</li>
	 * <li>campus zone: member day product for purpose work or education (PAID_CAMPUS_MEMBER), guest day product
	 * otherwise (PAID_CAMPUS_GUEST);</li>
	 * <li>chargeable minutes {@code ceil(chargeable_s / 60)} at most the free-stay threshold: 0, FREE_WITHIN_LIMIT;</li>
	 * <li>chargeable minutes above the maximum stay: the long-stay product, PAID_LONG_STAY;</li>
	 * <li>otherwise the first-period block (charged in full once any part of it is used) plus
	 * {@code (units * billing_unit_min * hourly_rate_cents + 30) / 60} for the rest, with
	 * {@code units = ceil(remaining_s / (billing_unit_min * 60))}, capped by the daily cap: PAID_METERED, or
	 * FREE_WITHIN_LIMIT when that price is 0.</li>
	 * </ol>
	 * The stay is validated before any rule, so an invalid stay fails even for a purpose that would be free.
	 *
	 * @param tariff tariff of the zone the activity lies in
	 * @param arrival_s car arrival in simulation seconds
	 * @param departure_s activity departure in simulation seconds; {@link #terminalDeparture_s} for the last activity
	 * @param purpose activity purpose (the MATSim activity type, e.g. home, work, education, shop)
	 * @param parkingFree value of the activity attribute parkingFree (free parking provided by the employer)
	 * @param residentOfZone the person is a resident of this zone
	 * @throws IllegalArgumentException for a non-finite or negative time or a departure before the arrival
	 * @throws ArithmeticException when a price leaves the long range (an implausible tariff) instead of wrapping around
	 */
	public static Result cents(ZoneTariff tariff, double arrival_s, double departure_s, String purpose, boolean parkingFree,
			boolean residentOfZone) {
		Objects.requireNonNull(tariff, "tariff");
		Objects.requireNonNull(purpose, "purpose");
		long chargeable_s = chargeableSeconds(arrival_s, departure_s, tariff.feeStart_s(), tariff.feeEnd_s());
		if (HOME_PURPOSE.equals(purpose)) {
			return new Result(0, ParkingOutcome.HOME);
		}
		if (parkingFree) {
			return new Result(0, ParkingOutcome.EMPLOYER_FREE);
		}
		if (tariff.residentExempt() && residentOfZone) {
			return new Result(0, ParkingOutcome.RESIDENT_FREE);
		}
		if (chargeable_s == 0) {
			return new Result(0, ParkingOutcome.OUTSIDE_FEE_HOURS);
		}
		if (tariff.zoneType() == ZoneTariff.ZoneType.CAMPUS) {
			if (CAMPUS_MEMBER_PURPOSES.contains(purpose)) {
				return new Result(tariff.memberDayCents().getAsLong(), ParkingOutcome.PAID_CAMPUS_MEMBER);
			}
			return new Result(tariff.guestDayCents().getAsLong(), ParkingOutcome.PAID_CAMPUS_GUEST);
		}
		long chargeable_min = Math.ceilDiv(chargeable_s, SECONDS_PER_MINUTE);
		if (tariff.freeIfStayAtMostMinutes().isPresent() && chargeable_min <= tariff.freeIfStayAtMostMinutes().getAsInt()) {
			return new Result(0, ParkingOutcome.FREE_WITHIN_LIMIT);
		}
		if (tariff.maxStayMinutes().isPresent() && chargeable_min > tariff.maxStayMinutes().getAsInt()) {
			return new Result(tariff.longStayProductCents().getAsLong(), ParkingOutcome.PAID_LONG_STAY);
		}
		long remaining_s = chargeable_s;
		long cents = 0;
		if (tariff.firstPeriodMinutes().isPresent()) {
			// The first period is sold as one block: charged in full as soon as any part of it is used.
			cents = tariff.firstPeriodCents().getAsLong();
			remaining_s -= Math.min(remaining_s, tariff.firstPeriodMinutes().getAsInt() * (long) SECONDS_PER_MINUTE);
		}
		cents = Math.addExact(cents, meteredCents(tariff, remaining_s));
		if (tariff.dailyCapCents().isPresent()) {
			cents = Math.min(cents, tariff.dailyCapCents().getAsLong());
		}
		return new Result(cents, cents == 0 ? ParkingOutcome.FREE_WITHIN_LIMIT : ParkingOutcome.PAID_METERED);
	}

	/**
	 * Started billing units at the hourly rate, rounded half up to the cent (section 3.2):
	 * {@code units = ceil(remaining_s / (billing_unit_min * 60))}, price
	 * {@code (units * billing_unit_min * hourly_rate_cents + 30) / 60} in euro cents.
	 */
	private static long meteredCents(ZoneTariff tariff, long remaining_s) {
		if (tariff.billingUnitMinutes().isEmpty()) {
			// ZoneTariff admits a missing billing unit only for a resident zone, whose hourly rate is 0 (design 3.1): the
			// formula then yields (units * unit * 0 + 30) / 60 = 0 for every unit count, so no unit is needed.
			return 0;
		}
		long billingUnitMinutes = tariff.billingUnitMinutes().getAsInt();
		long units = Math.ceilDiv(remaining_s, billingUnitMinutes * SECONDS_PER_MINUTE);
		long billedMinutes = Math.multiplyExact(units, billingUnitMinutes);
		long billedMinutesTimesHourlyRate = Math.multiplyExact(billedMinutes, tariff.hourlyRateCents().getAsLong());
		return Math.addExact(billedMinutesTimesHourlyRate, MINUTES_PER_HOUR / 2) / MINUTES_PER_HOUR;
	}

	/** Seconds of [arrival, departure) inside the fee window of simulated day {@code day} (0 = the first day). */
	private static long feeWindowOverlap_s(long arrival, long departure, long day, int feeStart_s, int feeEnd_s) {
		long dayStart_s = Math.multiplyExact(day, SECONDS_PER_DAY);
		long windowStart_s = Math.addExact(dayStart_s, feeStart_s);
		long windowEnd_s = Math.addExact(dayStart_s, feeEnd_s);
		return Math.max(0, Math.min(departure, windowEnd_s) - Math.max(arrival, windowStart_s));
	}

	/** Fails unless {@code 0 <= feeStart_s < feeEnd_s <= 86400}: one weekday fee window in seconds after midnight. */
	static void requireFeeWindow(int feeStart_s, int feeEnd_s, String owner) {
		if (feeStart_s < 0 || feeStart_s >= feeEnd_s || feeEnd_s > SECONDS_PER_DAY) {
			throw new IllegalArgumentException(owner + ": the fee window must satisfy 0 <= fee_start_s < fee_end_s <= "
					+ SECONDS_PER_DAY + ", got fee_start_s=" + feeStart_s + ", fee_end_s=" + feeEnd_s);
		}
	}

	/** A MATSim time floored to whole seconds; NaN, infinities, negative times and values beyond the long range fail. */
	private static long wholeSeconds(double time_s, String name) {
		if (!(time_s >= 0.0 && time_s < LONG_SECONDS_LIMIT)) {
			throw new IllegalArgumentException(name + " must be a finite, non-negative simulation time in seconds, got "
					+ time_s);
		}
		return (long) Math.floor(time_s);
	}
}
