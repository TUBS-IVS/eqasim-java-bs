package org.eqasim.braunschweig.parking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Tariff facts of one parking zone as the tariff model JSON states them (schema 1 of design "parking cost zones",
 * section 5.4, and schema 2 of the v2 levers and the resident districts, eqasim-bs issue #436): money in integer euro
 * cents, durations in whole minutes, the fee windows in seconds after midnight of the simulated weekday. An empty
 * optional is a JSON null, i.e. "not applicable in this zone"; the record holds no defaults and no tariff constants.
 * ParkingTariffs builds it from the JSON, ParkingCostCalculator prices stays with it.
 *
 * <p>The compact constructor applies the rules of the Python reference {@code braunschweig.parking.cost.ZoneTariff}
 * ({@code _check_tariff}), which validates every tariff row before the exporter writes the tariff model, so that this
 * reader is never more lenient than the exporter; every instance can then be priced without further checks:
 * <ul>
 * <li>every zone: {@code 0 <= feeStart_s < feeEnd_s <= 86400} and no negative money;</li>
 * <li>per zone type, the fields it requires and the fields it does not have, which must be null ({@link ZoneType}):
 * {@code street_paid} requires hourly rate and billing unit; {@code resident_zone} hourly rate, billing unit and
 * maximum stay (design section 3.1 does not list the billing unit, but the metered step of section 3.2 divides by it for
 * every non-campus zone) and has no commuter product; {@code campus} requires member and guest day products and has no
 * street and no garage fields;</li>
 * <li>set together or both null: the street and the garage first-period length and price;</li>
 * <li>the garage core (hourly rate, billing unit, fee window start and end) is given completely or not at all (spec
 * amendment A6); the garage day cap and the garage first period are optional, but only together with the core, because
 * without it they would never be priced; a garage cap of null means no cap;</li>
 * <li>a long-stay product needs a maximum stay (it is sold only above it); a resident zone's maximum stay needs a long-stay
 * product or the garage core, because a stay above the maximum stay loses the street product (spec lever 2) and the commuter
 * product alone does not suffice, as it serves work and education only. Spec v3 A1 (deliberate change of the v2 rule, ADR-0142
 * decision 1): a street_paid zone may state a maximum stay without a long-stay product and without a garage core; its stays above
 * the maximum are then served by the garage options within reach or, where there are none, priced as the street and named
 * NO_FEASIBLE_OPTION;</li>
 * <li>spec v3 A5: {@code ev_free_minutes} is at least 1 when set and exists on a street_paid zone only (a campus car park is
 * the university's, a resident zone charges visitors by disc, not by the town's fee ordinance);</li>
 * <li>{@code resident_zone}: {@code resident_exempt = true} and an hourly rate of exactly 0 (disc parking for
 * non-residents);</li>
 * <li>a daily cap at least the first-period price when both are set, for the street and for the garage columns: every
 * metered stay buys the first period, so a lower cap contradicts the tariff (a rule eqasim-bs issue #436 adds to both
 * implementations);</li>
 * <li>{@code campus}: {@code resident_permits_valid} is false, because no source says that a campus car park honours
 * resident parking permits (ASSUMPTION R2-a).</li>
 * </ul>
 * Fields that the pseudo-code of design section 3.2 tests for truthiness ({@code if t.daily_cap_cents: ...}) must be
 * positive when set: there a 0 reads as "absent", while an optional holding 0 is present, so a 0 could make a literal
 * (Python) port and this one disagree; a zero cap, threshold, billing unit, first period or maximum stay has no tariff
 * meaning either. The same holds for the garage billing unit, first period and day cap.
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
 * @param feeStart_s start of the daily street fee window in seconds after midnight
 * @param feeEnd_s end of the daily street fee window in seconds after midnight (exclusive)
 * @param residentExempt residents of this zone park free
 * @param garageHourlyRateCents garage product: price in euro cents per hour (core field)
 * @param garageBillingUnitMinutes garage product: length of one started billing unit in minutes (core field)
 * @param garageFirstPeriodMinutes garage product: length of the first-period block in minutes, sold as a whole
 * @param garageFirstPeriodCents garage product: price of the first-period block in euro cents
 * @param garageDailyCapCents garage product: upper bound of the price of one stay in euro cents; empty: no cap
 * @param garageFeeStart_s garage product: start of the garage's own daily fee window in seconds after midnight (core field)
 * @param garageFeeEnd_s garage product: end of the garage's own daily fee window in seconds after midnight, exclusive
 *        (core field)
 * @param commuterDayCents commuter product in euro cents per working day, offered to work and education stays
 * @param searchTimeMinutes parking search time in minutes of a destination in this zone (lever 4); not a price input
 * @param residentPermitsValid whether the parking honours resident parking permits, i.e. whether rule R2 frees a stay of a
 *        resident of the district there (ASSUMPTION R2-a); it gates R2 only, never the exemption of residents of the zone
 * @param evFreeMinutes tariff schema 5 (spec v3 A5, ASSUMPTIONS E1 and E2): the longest chargeable stay in minutes of an
 *        electric car that parks free on the street of the zone, within the zone's maximum stay; empty: no exemption (the
 *        rule was never stated, the export switched an expired rule off, or the model has schema 4 or older)
 */
public record ZoneTariff(String zoneId, ZoneType zoneType, OptionalLong hourlyRateCents, OptionalInt billingUnitMinutes,
		OptionalInt freeIfStayAtMostMinutes, OptionalInt firstPeriodMinutes, OptionalLong firstPeriodCents,
		OptionalLong dailyCapCents, OptionalInt maxStayMinutes, OptionalLong longStayProductCents,
		OptionalLong memberDayCents, OptionalLong guestDayCents, int feeStart_s, int feeEnd_s, boolean residentExempt,
		OptionalLong garageHourlyRateCents, OptionalInt garageBillingUnitMinutes, OptionalInt garageFirstPeriodMinutes,
		OptionalLong garageFirstPeriodCents, OptionalLong garageDailyCapCents, OptionalInt garageFeeStart_s,
		OptionalInt garageFeeEnd_s, OptionalLong commuterDayCents, OptionalInt searchTimeMinutes,
		boolean residentPermitsValid, OptionalInt evFreeMinutes) {

	/**
	 * Nullable fields (JSON names) that are set together or both null, as in the Python reference's
	 * {@code _PAIRED_FIELDS}: a first period has a length and a price.
	 */
	private static final List<List<String>> PAIRED_FIELDS = List.of(List.of("first_period_min", "first_period_cents"),
			List.of("garage_first_period_min", "garage_first_period_cents"));

	/** The garage core (JSON names), given completely or not at all (spec amendment A6). */
	private static final List<String> GARAGE_CORE_FIELDS = List.of("garage_hourly_rate_cents", "garage_billing_unit_min",
			"garage_fee_start_s", "garage_fee_end_s");

	/** The optional garage fields (JSON names), which are priced only together with the garage core. */
	private static final List<String> GARAGE_OPTION_FIELDS = List.of("garage_daily_cap_cents", "garage_first_period_min",
			"garage_first_period_cents");

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
		RESIDENT_ZONE("resident_zone", List.of("hourly_rate_cents", "billing_unit_min", "max_stay_min"),
				List.of("free_if_stay_at_most_min", "first_period_min", "first_period_cents", "daily_cap_cents",
						"member_day_cents", "guest_day_cents", "commuter_day_cents", "ev_free_minutes")),
		/** Institutional area selling member and guest day products (TU Braunschweig). */
		CAMPUS("campus", List.of("member_day_cents", "guest_day_cents"),
				List.of("hourly_rate_cents", "billing_unit_min", "free_if_stay_at_most_min", "first_period_min",
						"first_period_cents", "daily_cap_cents", "max_stay_min", "long_stay_product_cents",
						"garage_hourly_rate_cents", "garage_billing_unit_min", "garage_first_period_min",
						"garage_first_period_cents", "garage_daily_cap_cents", "garage_fee_start_s", "garage_fee_end_s",
						"ev_free_minutes"));

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

		/**
		 * Whether the parking of a zone of this type honours resident parking permits when the tariff does not say
		 * (ASSUMPTION R2-a): a street or resident zone does, a campus car park is the university's and honours none. The
		 * default of a schema-1 model, which has no such field.
		 */
		public boolean defaultResidentPermitsValid() {
			return this != CAMPUS;
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
		Objects.requireNonNull(garageHourlyRateCents, zone + "garage_hourly_rate_cents");
		Objects.requireNonNull(garageBillingUnitMinutes, zone + "garage_billing_unit_min");
		Objects.requireNonNull(garageFirstPeriodMinutes, zone + "garage_first_period_min");
		Objects.requireNonNull(garageFirstPeriodCents, zone + "garage_first_period_cents");
		Objects.requireNonNull(garageDailyCapCents, zone + "garage_daily_cap_cents");
		Objects.requireNonNull(garageFeeStart_s, zone + "garage_fee_start_s");
		Objects.requireNonNull(garageFeeEnd_s, zone + "garage_fee_end_s");
		Objects.requireNonNull(commuterDayCents, zone + "commuter_day_cents");
		Objects.requireNonNull(searchTimeMinutes, zone + "search_time_min");
		Objects.requireNonNull(evFreeMinutes, zone + "ev_free_minutes");
		ParkingCostCalculator.requireFeeWindow(feeStart_s, feeEnd_s, "parking zone " + zoneId);

		requireNonNegative(zone, "hourly_rate_cents", hourlyRateCents);
		requireNonNegative(zone, "first_period_cents", firstPeriodCents);
		requireNonNegative(zone, "long_stay_product_cents", longStayProductCents);
		requireNonNegative(zone, "member_day_cents", memberDayCents);
		requireNonNegative(zone, "guest_day_cents", guestDayCents);
		requireNonNegative(zone, "garage_hourly_rate_cents", garageHourlyRateCents);
		requireNonNegative(zone, "garage_first_period_cents", garageFirstPeriodCents);
		requireNonNegative(zone, "commuter_day_cents", commuterDayCents);
		requireNonNegativeMinutes(zone, "search_time_min", searchTimeMinutes);
		requirePositive(zone, "daily_cap_cents", dailyCapCents);
		requirePositive(zone, "billing_unit_min", billingUnitMinutes);
		requirePositive(zone, "free_if_stay_at_most_min", freeIfStayAtMostMinutes);
		requirePositive(zone, "first_period_min", firstPeriodMinutes);
		requirePositive(zone, "max_stay_min", maxStayMinutes);
		requirePositive(zone, "garage_daily_cap_cents", garageDailyCapCents);
		requirePositive(zone, "garage_billing_unit_min", garageBillingUnitMinutes);
		requirePositive(zone, "garage_first_period_min", garageFirstPeriodMinutes);
		requirePositive(zone, "ev_free_minutes", evFreeMinutes);

		// The nullable fields by JSON name (minutes and seconds widened to long) for the per-type and pairing rules.
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
		nullableFields.put("garage_hourly_rate_cents", garageHourlyRateCents);
		nullableFields.put("garage_billing_unit_min", widen(garageBillingUnitMinutes));
		nullableFields.put("garage_first_period_min", widen(garageFirstPeriodMinutes));
		nullableFields.put("garage_first_period_cents", garageFirstPeriodCents);
		nullableFields.put("garage_daily_cap_cents", garageDailyCapCents);
		nullableFields.put("garage_fee_start_s", widen(garageFeeStart_s));
		nullableFields.put("garage_fee_end_s", widen(garageFeeEnd_s));
		nullableFields.put("commuter_day_cents", commuterDayCents);
		nullableFields.put("search_time_min", widen(searchTimeMinutes));
		nullableFields.put("ev_free_minutes", widen(evFreeMinutes));

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
		requireGarageRules(zone, nullableFields, garageFeeStart_s, garageFeeEnd_s);
		if (longStayProductCents.isPresent() && maxStayMinutes.isEmpty()) {
			throw new IllegalArgumentException(zone + "long_stay_product_cents needs max_stay_min: a long-stay product"
					+ " without a maximum stay is never sold");
		}
		// Spec v3 A1 (ADR-0142 decision 1): a street_paid zone may state a maximum stay without anything to sell above it; its stays
		// above the maximum are then served by the garage options or priced as NO_FEASIBLE_OPTION. A resident zone keeps the
		// v2 rule: its maximum stay needs the long-stay product or the garage core.
		if (zoneType != ZoneType.STREET_PAID && maxStayMinutes.isPresent() && longStayProductCents.isEmpty()
				&& garageHourlyRateCents.isEmpty()) {
			throw new IllegalArgumentException(zone + "max_stay_min is set but neither long_stay_product_cents nor the garage"
					+ " core is: a stay above the maximum stay would have no product");
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
		requireCapNotBelowFirstPeriod(zone, "", dailyCapCents, firstPeriodCents);
		requireCapNotBelowFirstPeriod(zone, "garage_", garageDailyCapCents, garageFirstPeriodCents);
		if (zoneType == ZoneType.CAMPUS && residentPermitsValid) {
			throw new IllegalArgumentException(zone + "resident_permits_valid must be false for a campus zone: a campus car"
					+ " park does not honour resident parking permits (no source says it does)");
		}
	}

	/** Whether the zone has a garage product: its core is validated complete at construction. */
	public boolean hasGarage() {
		return garageHourlyRateCents.isPresent();
	}

	/** Whether the zone grants electric cars a free street stay up to {@code ev_free_minutes} (spec v3 A5). */
	public boolean hasEvExemption() {
		return evFreeMinutes.isPresent();
	}

	/** The parking search time of a destination in this zone in minutes (lever 4); 0 where the column is empty. */
	public int searchTimeMinutesOrZero() {
		return searchTimeMinutes.orElse(0);
	}

	/** Whether the zone carries a search time above 0 minutes: only in such a zone can lever 4 act. */
	public boolean hasSearchTime() {
		return searchTimeMinutesOrZero() > 0;
	}

	/**
	 * The garage rules of spec amendment A6: the core is complete or empty, the optional cap and first period need it, and
	 * the garage's own fee window satisfies {@code 0 <= start < end <= 86400}.
	 */
	private static void requireGarageRules(String zone, Map<String, OptionalLong> nullableFields,
			OptionalInt garageFeeStart_s, OptionalInt garageFeeEnd_s) {
		List<String> missingCoreFields = new ArrayList<>();
		for (String field : GARAGE_CORE_FIELDS) {
			if (nullableFields.get(field).isEmpty()) {
				missingCoreFields.add(field);
			}
		}
		if (!missingCoreFields.isEmpty() && missingCoreFields.size() < GARAGE_CORE_FIELDS.size()) {
			throw new IllegalArgumentException(zone + "the garage core " + GARAGE_CORE_FIELDS + " must be given completely"
					+ " or not at all; missing " + missingCoreFields);
		}
		if (!missingCoreFields.isEmpty()) {
			List<String> optionsWithoutCore = new ArrayList<>();
			for (String field : GARAGE_OPTION_FIELDS) {
				if (nullableFields.get(field).isPresent()) {
					optionsWithoutCore.add(field);
				}
			}
			if (!optionsWithoutCore.isEmpty()) {
				throw new IllegalArgumentException(zone + String.join(", ", optionsWithoutCore) + " need(s) the garage core "
						+ GARAGE_CORE_FIELDS + "; a garage cap or first period without a garage is never priced");
			}
			return;
		}
		int start_s = garageFeeStart_s.getAsInt();
		int end_s = garageFeeEnd_s.getAsInt();
		if (start_s < 0 || start_s >= end_s || end_s > ParkingCostCalculator.SECONDS_PER_DAY) {
			throw new IllegalArgumentException(zone + "garage fee window [" + start_s + ", " + end_s + ") s must satisfy"
					+ " 0 <= garage_fee_start_s < garage_fee_end_s <= " + ParkingCostCalculator.SECONDS_PER_DAY);
		}
	}

	/**
	 * Every metered stay buys the first period in full, so a daily cap below its price would replace the price of every
	 * metered stay by the cap: the tariff contradicts itself. Checked for the street ({@code prefix} empty) and the garage.
	 */
	private static void requireCapNotBelowFirstPeriod(String zone, String prefix, OptionalLong dailyCapCents,
			OptionalLong firstPeriodCents) {
		if (dailyCapCents.isPresent() && firstPeriodCents.isPresent()
				&& dailyCapCents.getAsLong() < firstPeriodCents.getAsLong()) {
			throw new IllegalArgumentException(zone + prefix + "daily_cap_cents " + dailyCapCents.getAsLong() + " is below "
					+ prefix + "first_period_cents " + firstPeriodCents.getAsLong()
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

	private static void requireNonNegativeMinutes(String zone, String field, OptionalInt minutes) {
		if (minutes.isPresent() && minutes.getAsInt() < 0) {
			throw new IllegalArgumentException(zone + field + " must be non-negative minutes or null, got "
					+ minutes.getAsInt());
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
