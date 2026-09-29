package org.eqasim.braunschweig.parking;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Tariff facts of one parking zone as the tariff model JSON states them (schema 1, design "parking cost zones",
 * section 5.4): money in integer euro cents, durations in whole minutes, the fee window in seconds after midnight of
 * the simulated weekday. An empty optional is a JSON null, i.e. "not applicable in this zone"; the record holds no
 * defaults and no tariff constants. ParkingTariffs builds it from the JSON, ParkingCostCalculator prices stays with it.
 *
 * <p>The compact constructor enforces the required fields per zone type of design section 3.1 and the pairing rules,
 * so every instance can be priced without further checks:
 * <ul>
 * <li>every zone: {@code 0 <= feeStart_s < feeEnd_s <= 86400}, no negative money, a maximum stay only together with a
 * long-stay product, a first-period length only together with a first-period price;</li>
 * <li>{@code street_paid}: hourly rate and billing unit;</li>
 * <li>{@code resident_zone}: maximum stay (hence a long-stay product), {@code resident_exempt = true} and an hourly rate
 * of exactly 0 (disc parking for non-residents); the billing unit may be absent because a zero rate needs none;</li>
 * <li>{@code campus}: member and guest day products.</li>
 * </ul>
 * Fields that the pseudo-code of design section 3.2 tests for truthiness ({@code if t.daily_cap_cents: ...}) must be
 * positive when set: there a 0 reads as "absent", while an optional holding 0 is present, so a 0 could make a literal
 * (Python) port and this one disagree; a zero cap, threshold, billing unit, first period or maximum stay has no tariff
 * meaning either.
 *
 * @param zoneId zone id as used in the activity attribute parkingZone
 * @param zoneType tariff regime of the zone
 * @param hourlyRateCents metered price in euro cents per hour
 * @param billingUnitMinutes length of one started billing unit in minutes
 * @param freeIfStayAtMostMinutes a stay of at most this many chargeable minutes is free
 * @param firstPeriodMinutes length of the first-period block in minutes, sold as a whole
 * @param firstPeriodCents price of the first-period block in euro cents
 * @param dailyCapCents upper bound of the metered price of one stay in euro cents
 * @param maxStayMinutes maximum chargeable stay in minutes; longer stays pay the long-stay product
 * @param longStayProductCents price in euro cents of a stay beyond the maximum stay
 * @param memberDayCents campus day product in euro cents for work and education stays
 * @param guestDayCents campus day product in euro cents for every other stay
 * @param feeStart_s start of the daily fee window in seconds after midnight
 * @param feeEnd_s end of the daily fee window in seconds after midnight (exclusive)
 * @param residentExempt residents of this zone park free
 */
public record ZoneTariff(String zoneId, ZoneType zoneType, OptionalLong hourlyRateCents, OptionalInt billingUnitMinutes,
		OptionalInt freeIfStayAtMostMinutes, OptionalInt firstPeriodMinutes, OptionalLong firstPeriodCents,
		OptionalLong dailyCapCents, OptionalInt maxStayMinutes, OptionalLong longStayProductCents,
		OptionalLong memberDayCents, OptionalLong guestDayCents, int feeStart_s, int feeEnd_s, boolean residentExempt) {

	/** Zone types of design section 3.1 with their JSON names. */
	public enum ZoneType {
		/** On-street or municipal-lot paid parking with one regime. */
		STREET_PAID("street_paid"),
		/** Bewohnerparkzone: residents exempt, disc parking with a maximum stay for everyone else. */
		RESIDENT_ZONE("resident_zone"),
		/** Institutional area selling member and guest day products (TU Braunschweig). */
		CAMPUS("campus");

		private final String jsonName;

		ZoneType(String jsonName) {
			this.jsonName = jsonName;
		}

		/** The name used by the tariff table and the tariff model JSON. */
		public String jsonName() {
			return jsonName;
		}

		/** The type with this JSON name; empty for an unknown name, which the caller must reject. */
		public static Optional<ZoneType> fromJsonName(String jsonName) {
			for (ZoneType type : values()) {
				if (type.jsonName.equals(jsonName)) {
					return Optional.of(type);
				}
			}
			return Optional.empty();
		}
	}

	public ZoneTariff {
		if (zoneId == null || zoneId.isBlank()) {
			throw new IllegalArgumentException("parking zone id must not be blank");
		}
		String zone = "parking zone " + zoneId + ": ";
		Objects.requireNonNull(zoneType, zone + "zone_type");
		Objects.requireNonNull(hourlyRateCents, zone + "hourly_rate_cents");
		Objects.requireNonNull(billingUnitMinutes, zone + "billing_unit_min");
		Objects.requireNonNull(freeIfStayAtMostMinutes, zone + "free_if_stay_at_most_min");
		Objects.requireNonNull(firstPeriodMinutes, zone + "first_period_min");
		Objects.requireNonNull(firstPeriodCents, zone + "first_period_cents");
		Objects.requireNonNull(dailyCapCents, zone + "daily_cap_cents");
		Objects.requireNonNull(maxStayMinutes, zone + "max_stay_min");
		Objects.requireNonNull(longStayProductCents, zone + "long_stay_product_cents");
		Objects.requireNonNull(memberDayCents, zone + "member_day_cents");
		Objects.requireNonNull(guestDayCents, zone + "guest_day_cents");
		ParkingCostCalculator.requireFeeWindow(feeStart_s, feeEnd_s, "parking zone " + zoneId);

		requireNonNegative(zone, "hourly_rate_cents", hourlyRateCents);
		requireNonNegative(zone, "first_period_cents", firstPeriodCents);
		requireNonNegative(zone, "long_stay_product_cents", longStayProductCents);
		requireNonNegative(zone, "member_day_cents", memberDayCents);
		requireNonNegative(zone, "guest_day_cents", guestDayCents);
		requirePositive(zone, "daily_cap_cents", dailyCapCents);
		requirePositive(zone, "billing_unit_min", billingUnitMinutes);
		requirePositive(zone, "free_if_stay_at_most_min", freeIfStayAtMostMinutes);
		requirePositive(zone, "first_period_min", firstPeriodMinutes);
		requirePositive(zone, "max_stay_min", maxStayMinutes);

		if (firstPeriodMinutes.isPresent() != firstPeriodCents.isPresent()) {
			throw new IllegalArgumentException(zone + "first_period_min and first_period_cents must be set together, got "
					+ firstPeriodMinutes + " and " + firstPeriodCents);
		}
		if (maxStayMinutes.isPresent() && longStayProductCents.isEmpty()) {
			throw new IllegalArgumentException(zone + "long_stay_product_cents is required when max_stay_min is set");
		}

		switch (zoneType) {
			case STREET_PAID -> {
				requirePresent(zone, "hourly_rate_cents", hourlyRateCents.isPresent(), zoneType);
				requirePresent(zone, "billing_unit_min", billingUnitMinutes.isPresent(), zoneType);
			}
			case RESIDENT_ZONE -> {
				requirePresent(zone, "max_stay_min", maxStayMinutes.isPresent(), zoneType);
				if (!residentExempt) {
					throw new IllegalArgumentException(zone + "resident_exempt must be true for a resident_zone");
				}
				if (hourlyRateCents.isEmpty() || hourlyRateCents.getAsLong() != 0) {
					throw new IllegalArgumentException(zone + "hourly_rate_cents must be 0 for a resident_zone (disc parking"
							+ " for non-residents, design section 3.1), got " + hourlyRateCents);
				}
			}
			case CAMPUS -> {
				requirePresent(zone, "member_day_cents", memberDayCents.isPresent(), zoneType);
				requirePresent(zone, "guest_day_cents", guestDayCents.isPresent(), zoneType);
			}
		}
	}

	private static void requirePresent(String zone, String field, boolean present, ZoneType zoneType) {
		if (!present) {
			throw new IllegalArgumentException(zone + field + " is required for a " + zoneType.jsonName());
		}
	}

	private static void requireNonNegative(String zone, String field, OptionalLong cents) {
		if (cents.isPresent() && cents.getAsLong() < 0) {
			throw new IllegalArgumentException(zone + field + " must be non-negative euro cents, got " + cents.getAsLong());
		}
	}

	private static void requirePositive(String zone, String field, OptionalLong value) {
		if (value.isPresent() && value.getAsLong() <= 0) {
			throw new IllegalArgumentException(zone + field + " must be positive or null, got " + value.getAsLong());
		}
	}

	private static void requirePositive(String zone, String field, OptionalInt value) {
		if (value.isPresent() && value.getAsInt() <= 0) {
			throw new IllegalArgumentException(zone + field + " must be positive or null, got " + value.getAsInt());
		}
	}
}
