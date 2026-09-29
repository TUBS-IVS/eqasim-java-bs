package org.eqasim.braunschweig.parking;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Tariff facts of one parking zone as the tariff model JSON states them (schema 1, design "parking cost zones",
 * section 5.4): money in integer euro cents, durations in whole minutes, the fee window in seconds after midnight of the
 * simulated weekday. An empty optional is a JSON null, i.e. "not applicable in this zone"; the record holds no defaults
 * and no tariff constants. ParkingTariffs builds it from the JSON, ParkingCostCalculator prices stays with it.
 *
 * <p>The compact constructor applies the rules of the Python reference {@code braunschweig.parking.cost.ZoneTariff}
 * ({@code _check_tariff}), which validates every tariff row before the exporter writes the tariff model, so that this
 * reader is never more lenient than the exporter; every instance can then be priced without further checks:
 * <ul>
 * <li>every zone: {@code 0 <= feeStart_s < feeEnd_s <= 86400} and no negative money;</li>
 * <li>per zone type, the fields it requires and the fields it does not have, which must be null ({@link ZoneType}):
 * {@code street_paid} requires hourly rate and billing unit; {@code resident_zone} hourly rate, billing unit, maximum
 * stay and long-stay product (design section 3.1 does not list the billing unit, but the metered step of section 3.2
 * divides by it for every non-campus zone); {@code campus} member and guest day products;</li>
 * <li>set together or both null: first-period length and price, maximum stay and long-stay product;</li>
 * <li>{@code resident_zone}: {@code resident_exempt = true} and an hourly rate of exactly 0 (disc parking for
 * non-residents);</li>
 * <li>a daily cap at least the first-period price when both are set: every metered stay buys the first period, so a
 * lower cap contradicts the tariff (a rule eqasim-bs issue #436 adds to both implementations).</li>
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

	/**
	 * Nullable fields (JSON names) that are set together or both null, as in the Python reference's
	 * {@code _PAIRED_FIELDS}: a first period has a length and a price, and only a stay beyond a maximum stay buys the
	 * long-stay product.
	 */
	private static final List<List<String>> PAIRED_FIELDS = List.of(List.of("first_period_min", "first_period_cents"),
			List.of("max_stay_min", "long_stay_product_cents"));

	/**
	 * Zone types of design section 3.1 with their JSON names and their nullable fields: the ones the type requires and the
	 * ones it does not have, field by field the tables {@code _REQUIRED_FIELDS} and {@code _NOT_APPLICABLE_FIELDS} of the
	 * Python reference. A field in neither list is optional for the type.
	 */
	public enum ZoneType {
		/** On-street or municipal-lot paid parking with one regime. */
		STREET_PAID("street_paid", List.of("hourly_rate_cents", "billing_unit_min"),
				List.of("member_day_cents", "guest_day_cents")),
		/** Bewohnerparkzone: residents exempt, disc parking with a maximum stay for everyone else. */
		RESIDENT_ZONE("resident_zone",
				List.of("hourly_rate_cents", "billing_unit_min", "max_stay_min", "long_stay_product_cents"),
				List.of("free_if_stay_at_most_min", "first_period_min", "first_period_cents", "daily_cap_cents",
						"member_day_cents", "guest_day_cents")),
		/** Institutional area selling member and guest day products (TU Braunschweig). */
		CAMPUS("campus", List.of("member_day_cents", "guest_day_cents"),
				List.of("hourly_rate_cents", "billing_unit_min", "free_if_stay_at_most_min", "first_period_min",
						"first_period_cents", "daily_cap_cents", "max_stay_min", "long_stay_product_cents"));

		private final String jsonName;
		private final List<String> requiredFields;
		private final List<String> notApplicableFields;

		ZoneType(String jsonName, List<String> requiredFields, List<String> notApplicableFields) {
			this.jsonName = jsonName;
			this.requiredFields = requiredFields;
			this.notApplicableFields = notApplicableFields;
		}

		/** The name used by the tariff table and the tariff model JSON. */
		public String jsonName() {
			return jsonName;
		}

		/** The nullable fields (JSON names) a zone of this type must set. */
		List<String> requiredFields() {
			return requiredFields;
		}

		/** The nullable fields (JSON names) a zone of this type does not have; they must be null. */
		List<String> notApplicableFields() {
			return notApplicableFields;
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

		// The nullable fields by JSON name (minutes widened to long) for the per-type and pairing rules.
		Map<String, OptionalLong> nullableFields = new LinkedHashMap<>();
		nullableFields.put("hourly_rate_cents", hourlyRateCents);
		nullableFields.put("billing_unit_min", widen(billingUnitMinutes));
		nullableFields.put("free_if_stay_at_most_min", widen(freeIfStayAtMostMinutes));
		nullableFields.put("first_period_min", widen(firstPeriodMinutes));
		nullableFields.put("first_period_cents", firstPeriodCents);
		nullableFields.put("daily_cap_cents", dailyCapCents);
		nullableFields.put("max_stay_min", widen(maxStayMinutes));
		nullableFields.put("long_stay_product_cents", longStayProductCents);
		nullableFields.put("member_day_cents", memberDayCents);
		nullableFields.put("guest_day_cents", guestDayCents);

		for (String field : zoneType.requiredFields()) {
			if (nullableFields.get(field).isEmpty()) {
				throw new IllegalArgumentException(zone + field + " is required for a " + zoneType.jsonName());
			}
		}
		for (String field : zoneType.notApplicableFields()) {
			OptionalLong value = nullableFields.get(field);
			if (value.isPresent()) {
				// The cost rules of the type never read the field: a value there would be silently ignored.
				throw new IllegalArgumentException(zone + field + " does not apply to a " + zoneType.jsonName()
						+ " zone and must be null, got " + value.getAsLong());
			}
		}
		for (List<String> pair : PAIRED_FIELDS) {
			OptionalLong first = nullableFields.get(pair.get(0));
			OptionalLong second = nullableFields.get(pair.get(1));
			if (first.isPresent() != second.isPresent()) {
				throw new IllegalArgumentException(zone + pair.get(0) + " and " + pair.get(1)
						+ " must be set together or both be null, got " + text(first) + " and " + text(second));
			}
		}
		if (zoneType == ZoneType.RESIDENT_ZONE) {
			if (!residentExempt) {
				throw new IllegalArgumentException(zone + "resident_exempt must be true for a resident_zone");
			}
			if (hourlyRateCents.getAsLong() != 0) {
				throw new IllegalArgumentException(zone + "hourly_rate_cents must be 0 for a resident_zone (disc parking"
						+ " for non-residents, design section 3.1), got " + hourlyRateCents.getAsLong());
			}
		}
		if (dailyCapCents.isPresent() && firstPeriodCents.isPresent()
				&& dailyCapCents.getAsLong() < firstPeriodCents.getAsLong()) {
			throw new IllegalArgumentException(zone + "daily_cap_cents " + dailyCapCents.getAsLong()
					+ " is below first_period_cents " + firstPeriodCents.getAsLong()
					+ ": every metered stay buys the first period, so a lower cap contradicts the tariff");
		}
	}

	private static OptionalLong widen(OptionalInt value) {
		return value.isPresent() ? OptionalLong.of(value.getAsInt()) : OptionalLong.empty();
	}

	private static String text(OptionalLong value) {
		return value.isPresent() ? Long.toString(value.getAsLong()) : "null";
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
