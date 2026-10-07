package org.eqasim.braunschweig.parking;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Parking cost of one car stay in one zone: a pure port of design "parking cost zones", section 3.2, and of the v2
 * product minimum (eqasim-bs issue #436). The Python reference braunschweig.parking.cost implements the same rules; the
 * 90 golden cases of {@code parking_golden_cases.json} pin both implementations to identical cents and outcomes.
 *
 * <p>Units: money in integer euro cents, times in whole simulation seconds (seconds after midnight of the first
 * simulated day, possibly beyond 24 h), tariff durations in minutes. MATSim passes times as doubles; they are floored to
 * whole seconds before any arithmetic, so sub-second noise never starts an extra minute or billing unit. The arithmetic
 * is integer and overflow-checked ({@code Math.*Exact}); no floating-point money is ever formed.
 *
 * <p>Assumptions (labels of the design's assumptions register): D1 the simulated day is an average weekday and the fee
 * window repeats every 86,400 s; T1 a car at the last activity of the plan pays until the fee window of its arrival day
 * ends ({@link #terminalDeparture_s}); M1 the maximum stay is compared with the chargeable duration, because the
 * Braunschweig ordinance restricts the stay during fee hours only, and other municipalities are treated the same, and a
 * stay above it loses the street product where the zone has no long-stay product; L1 every priced stay lasts at least
 * the minimum parked duration of the run, applied before the tariff rules ({@link #minimumStayDeparture_s}); P1 a stay
 * pays the cheapest product the driver can use (street, garage, commuter), every product pricing the same L1-extended
 * interval (spec amendment A3); P2 the commuter product is offered to work and education only; R1 and R2 a resident of
 * the zone, and a person staying in the resident parking district of the home in a zone that honours resident permits
 * (R2-a), park free.
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

	/**
	 * Purposes offered the commuter product, on street and campus zones (assumption P2): regular commuters hold the
	 * cheapest long-term product, so it is an effective daily cost for work and education, never a day tariff.
	 */
	public static final Set<String> COMMUTER_PURPOSES = Set.of("work", "education");

	/** Also converts the minimum parked duration of ParkingConfigGroup from minutes to seconds. */
	static final int SECONDS_PER_MINUTE = 60;
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
	 * Whether the purpose is the home purpose: the one test of the home rule H1 (a home stay is free) and of the parking
	 * search time (a home destination gets none, lever 4), so that both agree on what home is. The purpose is the MATSim
	 * activity type of the destination.
	 */
	public static boolean isHomePurpose(String purpose) {
		return HOME_PURPOSE.equals(purpose);
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
		requireDepartureNotBeforeArrival(arrival_s, departure_s);
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
	 * Departure a stay is priced to under the minimum parked duration (assumption L1):
	 * {@code max(departure_s, arrival_s + minimumStay_s)}, so the priced stay [arrival_s, that departure) lasts at least
	 * {@code minimumStay_s}. Applied before every rule of {@link #cents}, which then prices the extended stay unchanged
	 * (fee window, free-stay threshold, first period, maximum stay, daily cap, campus products; the home, employer-free
	 * and resident rules price 0 whatever the duration). For the last activity of a plan {@code departure_s} is the
	 * departure of the terminal-stay rule ({@link #terminalDeparture_s}), so the minimum applies after that rule. A
	 * minimum of 0 returns {@code departure_s}, the version 1 pricing. The result prices the stay only; it never changes
	 * the simulated timing. It is not floored; {@link #cents} floors it.
	 *
	 * @param arrival_s car arrival in simulation seconds
	 * @param departure_s departure of the simulated stay in simulation seconds
	 * @param minimumStay_s minimum parked duration in whole seconds, at least 0
	 * @throws IllegalArgumentException for a negative minimum, a non-finite or negative time, or a departure before the
	 *         arrival, which the minimum would otherwise silently turn into a valid stay
	 */
	public static double minimumStayDeparture_s(double arrival_s, double departure_s, int minimumStay_s) {
		if (minimumStay_s < 0) {
			throw new IllegalArgumentException("minimumStay_s must be non-negative whole seconds, got " + minimumStay_s);
		}
		requireSimulationTime(arrival_s, "arrival_s");
		requireSimulationTime(departure_s, "departure_s");
		requireDepartureNotBeforeArrival(arrival_s, departure_s);
		return Math.max(departure_s, arrival_s + minimumStay_s);
	}

	/**
	 * Price and deciding rule of one stay in the zone of {@code tariff}; the rules of section 3.2 and of the v2 product
	 * minimum in this order:
	 * <ol>
	 * <li>purpose {@code home}: 0, HOME;</li>
	 * <li>{@code parkingFree}: 0, EMPLOYER_FREE;</li>
	 * <li>a resident-exempt zone and a resident of this zone (rule R1), or a stay in the resident district of the person's
	 * home ({@code residentOfDistrict}, rule R2) in a zone that honours resident permits
	 * ({@link ZoneTariff#residentPermitsValid}, ASSUMPTION R2-a): 0, RESIDENT_FREE. R2 reads neither the zone type nor
	 * the exemption; the permit flag switches off R2 only, never R1;</li>
	 * <li>no chargeable second in the STREET fee window: 0, OUTSIDE_FEE_HOURS (a free street beats every garage);</li>
	 * <li>campus zone: the member day product for purpose work or education (PAID_CAMPUS_MEMBER), or the commuter product
	 * where the zone has a cheaper one (PAID_COMMUTER; a tie keeps the member day product), the guest day product
	 * otherwise (PAID_CAMPUS_GUEST);</li>
	 * <li>street and resident zones: the cheapest product the driver can use (assumption P1), a tie going to the first of
	 * the street product, the garage product and the commuter product:
	 * <ul>
	 * <li>the street product: chargeable minutes {@code ceil(chargeable_s / 60)} at most the free-stay threshold: 0,
	 * FREE_WITHIN_LIMIT; above the maximum stay the long-stay product, PAID_LONG_STAY, or no street product where the zone
	 * has none; otherwise the first-period block (charged in full once any part of it is used) plus
	 * {@code (units * billing_unit_min * hourly_rate_cents + 30) / 60} for the rest, with
	 * {@code units = ceil(remaining_s / (billing_unit_min * 60))}, capped by the daily cap: PAID_METERED, or
	 * FREE_WITHIN_LIMIT when that price is 0;</li>
	 * <li>the garage product: the same metering on the garage columns over the seconds in the garage's own fee window,
	 * without free threshold and maximum stay and with a daily cap only where the zone has one: PAID_GARAGE, or
	 * FREE_WITHIN_LIMIT when that price is 0;</li>
	 * <li>the commuter product {@code commuter_day_cents} for work and education: PAID_COMMUTER.</li>
	 * </ul>
	 * </li>
	 * </ol>
	 * A schema-1 tariff has only the street product, so it prices exactly as under version 1. The stay is validated before
	 * any rule, so an invalid stay fails even for a purpose that would be free.
	 *
	 * @param tariff tariff of the zone the activity lies in
	 * @param arrival_s car arrival in simulation seconds
	 * @param departure_s departure the stay is priced to, in simulation seconds: the activity departure, or
	 *        {@link #terminalDeparture_s} for the last activity, extended by {@link #minimumStayDeparture_s} (assumption L1);
	 *        every product prices this one interval (spec amendment A3), and a garage window never extends it
	 * @param purpose activity purpose (the MATSim activity type, e.g. home, work, education, shop)
	 * @param parkingFree value of the activity attribute parkingFree (free parking provided by the employer)
	 * @param residentOfZone the person is a resident of this zone (rule R1)
	 * @param residentOfDistrict the activity lies in the resident parking district of the person's home (rule R2): the
	 *        activity attribute parkingDistrict and the person attribute residentParkingDistrict are both set and equal
	 * @throws IllegalArgumentException for a non-finite or negative time or a departure before the arrival
	 * @throws ArithmeticException when a price leaves the long range (an implausible tariff) instead of wrapping around
	 */
	public static Result cents(ZoneTariff tariff, double arrival_s, double departure_s, String purpose, boolean parkingFree,
			boolean residentOfZone, boolean residentOfDistrict) {
		Objects.requireNonNull(tariff, "tariff");
		Objects.requireNonNull(purpose, "purpose");
		long chargeable_s = chargeableSeconds(arrival_s, departure_s, tariff.feeStart_s(), tariff.feeEnd_s());
		if (isHomePurpose(purpose)) {
			return new Result(0, ParkingOutcome.HOME);
		}
		if (parkingFree) {
			return new Result(0, ParkingOutcome.EMPLOYER_FREE);
		}
		if ((tariff.residentExempt() && residentOfZone) || (residentOfDistrict && tariff.residentPermitsValid())) {
			return new Result(0, ParkingOutcome.RESIDENT_FREE);
		}
		if (chargeable_s == 0) {
			return new Result(0, ParkingOutcome.OUTSIDE_FEE_HOURS);
		}
		if (tariff.zoneType() == ZoneTariff.ZoneType.CAMPUS) {
			if (CAMPUS_MEMBER_PURPOSES.contains(purpose)) {
				return cheapest(new Result(tariff.memberDayCents().getAsLong(), ParkingOutcome.PAID_CAMPUS_MEMBER),
						commuterProduct(tariff, purpose));
			}
			return new Result(tariff.guestDayCents().getAsLong(), ParkingOutcome.PAID_CAMPUS_GUEST);
		}
		return cheapest(streetProduct(tariff, chargeable_s), garageProduct(tariff, arrival_s, departure_s),
				commuterProduct(tariff, purpose));
	}

	/**
	 * Rules 6 to 8 of design section 3.2 on the street columns (the version 1 formula), or null where the stay may not use
	 * the street: above the maximum stay the zone sells the long-stay product, or no street product at all (spec lever 2;
	 * ZoneTariff then guarantees a garage). {@code chargeable_s} (above 0) are the seconds in the STREET fee window.
	 */
	static Result streetProduct(ZoneTariff tariff, long chargeable_s) {
		long chargeable_min = Math.ceilDiv(chargeable_s, SECONDS_PER_MINUTE);
		if (tariff.freeIfStayAtMostMinutes().isPresent() && chargeable_min <= tariff.freeIfStayAtMostMinutes().getAsInt()) {
			return new Result(0, ParkingOutcome.FREE_WITHIN_LIMIT);
		}
		if (tariff.maxStayMinutes().isPresent() && chargeable_min > tariff.maxStayMinutes().getAsInt()) {
			if (tariff.longStayProductCents().isEmpty()) {
				return null;
			}
			return new Result(tariff.longStayProductCents().getAsLong(), ParkingOutcome.PAID_LONG_STAY);
		}
		// ZoneTariff guarantees the billing unit and the hourly rate of every zone type that reaches this step
		// (street_paid, resident_zone); campus zones are priced by their day products before it.
		long cents = meteredCents(chargeable_s, tariff.hourlyRateCents().getAsLong(), tariff.billingUnitMinutes().getAsInt(),
				tariff.firstPeriodMinutes(), tariff.firstPeriodCents(), tariff.dailyCapCents());
		return new Result(cents, cents == 0 ? ParkingOutcome.FREE_WITHIN_LIMIT : ParkingOutcome.PAID_METERED);
	}

	/**
	 * The garage product, or null without a garage core: the metering primitive on the garage columns over the seconds of
	 * the stay inside the garage's OWN fee window; no free threshold, no maximum stay, a daily cap only where the zone
	 * gives one (spec amendment A6). A price of 0 ct, for instance for a stay outside the garage window, is
	 * FREE_WITHIN_LIMIT; PAID_GARAGE always means more than 0 ct.
	 */
	private static Result garageProduct(ZoneTariff tariff, double arrival_s, double departure_s) {
		if (!tariff.hasGarage()) {
			return null;
		}
		// ZoneTariff guarantees the complete garage core whenever the hourly rate is present.
		long garage_s = chargeableSeconds(arrival_s, departure_s, tariff.garageFeeStart_s().getAsInt(),
				tariff.garageFeeEnd_s().getAsInt());
		long cents = meteredCents(garage_s, tariff.garageHourlyRateCents().getAsLong(),
				tariff.garageBillingUnitMinutes().getAsInt(), tariff.garageFirstPeriodMinutes(),
				tariff.garageFirstPeriodCents(), tariff.garageDailyCapCents());
		return new Result(cents, cents == 0 ? ParkingOutcome.FREE_WITHIN_LIMIT : ParkingOutcome.PAID_GARAGE);
	}

	/** The commuter product per working day for the commuter purposes (assumption P2), or null. */
	static Result commuterProduct(ZoneTariff tariff, String purpose) {
		if (tariff.commuterDayCents().isEmpty() || !COMMUTER_PURPOSES.contains(purpose)) {
			return null;
		}
		return new Result(tariff.commuterDayCents().getAsLong(), ParkingOutcome.PAID_COMMUTER);
	}

	/**
	 * The cheapest of the available (non-null) products (assumption P1); a tie goes to the product listed first, i.e. the
	 * street, then the garage, then the commuter product (on a campus the member day product, then the commuter product).
	 * ZoneTariff guarantees at least one available product for every stay that reaches the product minimum.
	 */
	private static Result cheapest(Result... products) {
		Result cheapest = null;
		for (Result product : products) {
			if (product != null && (cheapest == null || product.cents() < cheapest.cents())) {
				cheapest = product;
			}
		}
		if (cheapest == null) {
			throw new IllegalStateException(
					"no parking product is available for the stay; ZoneTariff guarantees one, so this is a defect");
		}
		return cheapest;
	}

	/**
	 * The metering primitive of design section 3.2 rule 8, shared by the street and the garage product, in integer cents:
	 * the first period is charged once in full when any part of it is used, the rest in started billing units at the
	 * hourly rate rounded half up to the cent, {@code units = ceil(remaining_s / (billing_unit_min * 60))} and price
	 * {@code (units * billing_unit_min * hourly_rate_cents + 30) / 60}, then the daily cap applies once per stay. Without a
	 * chargeable second nothing is used, not even the first period, so the price is 0 ct: the street product never meters
	 * such a stay (rule 4 returns before), but the garage meters its own fee window and can miss a stay the street charges.
	 */
	static long meteredCents(long chargeable_s, long hourlyRateCents, int billingUnitMinutes,
			OptionalInt firstPeriodMinutes, OptionalLong firstPeriodCents, OptionalLong dailyCapCents) {
		if (chargeable_s == 0) {
			return 0;
		}
		long remaining_s = chargeable_s;
		long cents = 0;
		if (firstPeriodMinutes.isPresent()) {
			// The first period is sold as one block: charged in full as soon as any part of it is used.
			cents = firstPeriodCents.getAsLong();
			remaining_s -= Math.min(remaining_s, firstPeriodMinutes.getAsInt() * (long) SECONDS_PER_MINUTE);
		}
		long units = Math.ceilDiv(remaining_s, billingUnitMinutes * (long) SECONDS_PER_MINUTE);
		long billedMinutes = Math.multiplyExact(units, (long) billingUnitMinutes);
		long billedMinutesTimesHourlyRate = Math.multiplyExact(billedMinutes, hourlyRateCents);
		cents = Math.addExact(cents, Math.addExact(billedMinutesTimesHourlyRate, MINUTES_PER_HOUR / 2) / MINUTES_PER_HOUR);
		if (dailyCapCents.isPresent()) {
			cents = Math.min(cents, dailyCapCents.getAsLong());
		}
		return cents;
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
	static long wholeSeconds(double time_s, String name) {
		requireSimulationTime(time_s, name);
		return (long) Math.floor(time_s);
	}

	/** Fails for NaN, infinities, negative times and values beyond the long range of whole seconds. */
	private static void requireSimulationTime(double time_s, String name) {
		if (!(time_s >= 0.0 && time_s < LONG_SECONDS_LIMIT)) {
			throw new IllegalArgumentException(name + " must be a finite, non-negative simulation time in seconds, got "
					+ time_s);
		}
	}

	/** Fails for a departure before the arrival: a parking stay needs departure >= arrival. */
	private static void requireDepartureNotBeforeArrival(double arrival_s, double departure_s) {
		if (departure_s < arrival_s) {
			throw new IllegalArgumentException("departure_s " + departure_s + " is before arrival_s " + arrival_s
					+ "; a parking stay needs departure >= arrival");
		}
	}
}
