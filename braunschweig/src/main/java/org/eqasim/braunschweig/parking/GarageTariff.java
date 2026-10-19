package org.eqasim.braunschweig.parking;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * One priced garage of the tariff model (schema 3, spec Amendment E1 and E6 of parking cost zones v2, eqasim-bs issue
 * #436): a point in the network CRS (EPSG:25832, metres) with its tariff in integer euro cents, minutes and seconds. The
 * Python reference is {@code braunschweig.parking.cost.GarageTariff}; this record applies its rules
 * ({@code _check_garage}) so that the reader is never more lenient than the exporter, and
 * {@link GarageOptionPricing} prices a stay with it exactly as {@code garage_metered_cents} does.
 *
 * <p>A garage has exactly one tariff structure:
 * <ul>
 * <li>the single-window form: {@code hourlyRateCents} per started {@code billingUnitMinutes} within the fee window
 * {@code [feeStart_s, feeEnd_s)}, optionally with a first period and a day cap;</li>
 * <li>the tiered form (spec E10, ASSUMPTION P6): {@code tiers}, a list of time-of-day tiers sharing one unit length and no
 * fee window; a first period (optionally tied to a clock window, spec E10 amendment) and a day cap are optional. It may
 * carry ONE closed free band in {@code bands} as its grace period (spec E14 amendment (1), ASSUMPTION P10): a stay not
 * longer than the band costs 0, a longer stay is priced by the tiers from its arrival. A tiered garage with a grace
 * period has no first period;</li>
 * <li>the banded form (spec E11, ASSUMPTION P8): {@code bands}, an ordered list of duration bands with the fee window and
 * an optional day cap, no rate and no first period. The free schedule of a car park that is free for every stay (E14) is
 * the one open free band.</li>
 * </ul>
 * A form is therefore decided by the presence of both lists, never by a name: {@link #hasTiers()} and {@link #hasBands()}
 * may both be true, and then the bands are the grace period.
 *
 * <p>An empty optional is a JSON null; an empty list is "the structure is absent" (the reader refuses a JSON {@code []}).
 *
 * @param garageId unique id; options are ordered by ascending id (the sum order of the expected cost)
 * @param x_m easting in metres, EPSG:25832
 * @param y_m northing in metres, EPSG:25832
 * @param hourlyRateCents single-window form: price in euro cents per hour
 * @param billingUnitMinutes single-window form: length of one started billing unit in minutes
 * @param feeStart_s start of the daily fee window in seconds after midnight (single-window and banded form)
 * @param feeEnd_s end of the daily fee window in seconds after midnight, exclusive (single-window and banded form)
 * @param firstPeriodMinutes length of the first-period block in minutes, sold as a whole
 * @param firstPeriodCents price of the first-period block in euro cents
 * @param firstPeriodStart_s start of the clock window the first period is tied to (ASSUMPTION P6 as amended)
 * @param firstPeriodEnd_s end of that clock window, exclusive
 * @param dailyCapCents upper bound of the price of one stay in euro cents; empty: no cap
 * @param tiers time-of-day tiers of the tiered form; empty: not a tiered garage
 * @param bands duration bands of the banded form or the grace period of a tiered garage; empty: none
 * @param monthlyCents monthly product in euro cents, used per working day by work and education stays (ASSUMPTION P2)
 * @param maxStayMinutes tariff schema 5 (spec v3 A1, ASSUMPTION M3): the longest ELAPSED stay in minutes the garage or lot
 *        admits; a longer stay leaves it out of the option set ({@link #admitsElapsedStay}); empty: no maximum, and the value
 *        of every garage of a schema-4 or older model
 */
public record GarageTariff(String garageId, double x_m, double y_m, OptionalLong hourlyRateCents,
		OptionalInt billingUnitMinutes, OptionalInt feeStart_s, OptionalInt feeEnd_s, OptionalInt firstPeriodMinutes,
		OptionalLong firstPeriodCents, OptionalInt firstPeriodStart_s, OptionalInt firstPeriodEnd_s,
		OptionalLong dailyCapCents, List<Tier> tiers, List<Band> bands, OptionalLong monthlyCents,
		OptionalInt maxStayMinutes) {

	/**
	 * One time-of-day tier of a tiered garage (spec E10): a started unit of {@code unitMinutes} minutes that begins at a time
	 * of day in {@code [start_s, end_s)} costs {@code priceCents}. {@code end_s <= start_s} crosses midnight; 86,400 is
	 * midnight itself.
	 *
	 * @param start_s start of the tier in seconds after midnight, inclusive, below 86,400
	 * @param end_s end of the tier in seconds after midnight, exclusive, at most 86,400, different from the start
	 * @param unitMinutes length of one started unit in minutes, positive
	 * @param priceCents price of one started unit in euro cents, positive
	 */
	public record Tier(int start_s, int end_s, int unitMinutes, long priceCents) {
		/** Whether a unit that begins at {@code timeOfDay_s} (seconds after midnight) lies in the tier. */
		public boolean covers(long timeOfDay_s) {
			if (end_s > start_s) {
				return start_s <= timeOfDay_s && timeOfDay_s < end_s;
			}
			return timeOfDay_s >= start_s || timeOfDay_s < end_s;
		}
	}

	/** The kind of a duration band (spec E11), with its JSON name. */
	public enum BandKind {
		/** The stay costs 0 while its duration lies in the band. */
		FREE("free"),
		/** The stay costs the band's price while its duration lies in the band: an absolute price, no addition. */
		TOTAL("total"),
		/** The price reached at the band's start plus the band's price per started unit counted from the band's start. */
		INCREMENT("increment");

		private final String jsonName;

		BandKind(String jsonName) {
			this.jsonName = jsonName;
		}

		/** The name used by the tariff model JSON. */
		public String jsonName() {
			return jsonName;
		}

		/** The kind with this JSON name, or empty. */
		public static Optional<BandKind> fromJsonName(String name) {
			for (BandKind kind : values()) {
				if (kind.jsonName.equals(name)) {
					return Optional.of(kind);
				}
			}
			return Optional.empty();
		}
	}

	/**
	 * One band of a banded garage (spec E11) over the elapsed duration d in minutes: {@code fromMinutes < d <= toMinutes}.
	 *
	 * @param fromMinutes start of the band in minutes, exclusive; 0 for the first band
	 * @param toMinutes end of the band in minutes, inclusive; empty for the open-ended last band
	 * @param kind free, total or increment
	 * @param priceCents 0 for a free band, the stay price of a total band, the price per started unit of an increment band
	 * @param unitMinutes length of one started unit of an increment band in minutes; empty for the other kinds
	 */
	public record Band(int fromMinutes, OptionalInt toMinutes, BandKind kind, long priceCents, OptionalInt unitMinutes) {
	}

	/** Validates as the Python reference's {@code _check_garage} and fails with a message that names the garage. */
	public GarageTariff {
		Objects.requireNonNull(garageId, "garageId");
		if (garageId.isBlank()) {
			throw new IllegalArgumentException("garage id must be a non-empty string, got '" + garageId + "'");
		}
		String garage = "garage " + garageId + ": ";
		for (Object field : new Object[] { hourlyRateCents, billingUnitMinutes, feeStart_s, feeEnd_s, firstPeriodMinutes,
				firstPeriodCents, firstPeriodStart_s, firstPeriodEnd_s, dailyCapCents, tiers, bands, monthlyCents,
				maxStayMinutes }) {
			Objects.requireNonNull(field, garage + "no field may be null (use an empty optional or an empty list)");
		}
		tiers = List.copyOf(tiers);
		bands = List.copyOf(bands);
		if (!Double.isFinite(x_m) || !Double.isFinite(y_m)) {
			throw new IllegalArgumentException(garage + "x_m and y_m must be finite numbers of metres (EPSG:25832), got "
					+ x_m + " and " + y_m);
		}
		requireAtLeast(garage, "hourly_rate_cents", hourlyRateCents, 1);
		requireAtLeast(garage, "billing_unit_min", billingUnitMinutes, 1);
		requireAtLeast(garage, "fee_start_s", feeStart_s, 0);
		requireAtLeast(garage, "fee_end_s", feeEnd_s, 1);
		requireAtLeast(garage, "first_period_min", firstPeriodMinutes, 1);
		requireAtLeast(garage, "first_period_cents", firstPeriodCents, 1);
		requireAtLeast(garage, "first_period_start_s", firstPeriodStart_s, 0);
		requireAtLeast(garage, "first_period_end_s", firstPeriodEnd_s, 1);
		requireAtLeast(garage, "daily_cap_cents", dailyCapCents, 1);
		requireAtLeast(garage, "monthly_cents", monthlyCents, 1);
		requireAtLeast(garage, "max_stay_min", maxStayMinutes, 1);

		List<String> core = new ArrayList<>();
		addIfPresent(core, "hourly_rate_cents", hourlyRateCents.isPresent());
		addIfPresent(core, "billing_unit_min", billingUnitMinutes.isPresent());
		addIfPresent(core, "fee_start_s", feeStart_s.isPresent());
		addIfPresent(core, "fee_end_s", feeEnd_s.isPresent());
		if (!tiers.isEmpty()) {
			if (!core.isEmpty()) {
				throw new IllegalArgumentException(garage + "a tiered garage leaves the single-window core empty, found "
						+ core);
			}
			requireTiers(garage, tiers);
			if (!bands.isEmpty()) {
				requireGraceBand(garage, bands, firstPeriodMinutes);
			}
		} else if (!bands.isEmpty()) {
			List<String> rate = new ArrayList<>();
			addIfPresent(rate, "hourly_rate_cents", hourlyRateCents.isPresent());
			addIfPresent(rate, "billing_unit_min", billingUnitMinutes.isPresent());
			if (!rate.isEmpty()) {
				throw new IllegalArgumentException(garage + "a banded garage leaves the rate and the billing unit empty, found "
						+ rate);
			}
			if (feeStart_s.isEmpty() || feeEnd_s.isEmpty()) {
				throw new IllegalArgumentException(garage + "a banded garage needs its fee window (fee_start_s and fee_end_s)");
			}
			if (firstPeriodMinutes.isPresent()) {
				throw new IllegalArgumentException(garage
						+ "a banded garage has no first period (its first band is the first period)");
			}
			requireBands(garage, bands);
		} else if (core.size() != 4) {
			throw new IllegalArgumentException(garage + "a garage needs exactly one tariff structure: the complete"
					+ " single-window core (hourly_rate_cents, billing_unit_min, fee_start_s, fee_end_s; found " + core
					+ "), tiers or bands");
		}
		if (feeStart_s.isPresent() && feeEnd_s.isPresent() && !(feeStart_s.getAsInt() < feeEnd_s.getAsInt()
				&& feeEnd_s.getAsInt() <= ParkingCostCalculator.SECONDS_PER_DAY)) {
			throw new IllegalArgumentException(garage + "fee window [" + feeStart_s.getAsInt() + ", " + feeEnd_s.getAsInt()
					+ ") s must satisfy 0 <= start < end <= " + ParkingCostCalculator.SECONDS_PER_DAY);
		}
		if (firstPeriodMinutes.isPresent() != firstPeriodCents.isPresent()) {
			throw new IllegalArgumentException(garage
					+ "first_period_min and first_period_cents must be given together or both be empty");
		}
		if (firstPeriodStart_s.isPresent() != firstPeriodEnd_s.isPresent()) {
			throw new IllegalArgumentException(garage
					+ "first_period_start_s and first_period_end_s must be given together or both be empty");
		}
		if (firstPeriodStart_s.isPresent()) {
			if (firstPeriodMinutes.isEmpty()) {
				throw new IllegalArgumentException(garage + "a first-period window needs a first period");
			}
			if (!(firstPeriodStart_s.getAsInt() < firstPeriodEnd_s.getAsInt()
					&& firstPeriodEnd_s.getAsInt() <= ParkingCostCalculator.SECONDS_PER_DAY)) {
				throw new IllegalArgumentException(garage + "first-period window [" + firstPeriodStart_s.getAsInt() + ", "
						+ firstPeriodEnd_s.getAsInt() + ") s must satisfy 0 <= start < end <= "
						+ ParkingCostCalculator.SECONDS_PER_DAY);
			}
		}
		if (dailyCapCents.isPresent() && firstPeriodCents.isPresent()
				&& dailyCapCents.getAsLong() < firstPeriodCents.getAsLong()) {
			throw new IllegalArgumentException(garage + "daily_cap_cents " + dailyCapCents.getAsLong() + " is below"
					+ " first_period_cents " + firstPeriodCents.getAsLong());
		}
	}

	/** Whether the garage has the time-of-day tiers of spec E10 (the tiered form, with or without a grace period). */
	public boolean hasTiers() {
		return !tiers.isEmpty();
	}

	/**
	 * Whether the garage or lot admits a stay of {@code elapsed_s} ELAPSED seconds (spec v3 A1, ASSUMPTION M3), as the Python
	 * reference {@code option_feasible}: true without a maximum stay, else when the elapsed stay rounded up to whole minutes is
	 * at most {@code max_stay_min}. The elapsed stay (arrival to priced departure) is compared, not the chargeable minutes
	 * inside a fee window, because the car stands in the garage for all of it.
	 */
	public boolean admitsElapsedStay(long elapsed_s) {
		return maxStayMinutes.isEmpty()
				|| Math.ceilDiv(elapsed_s, (long) ParkingCostCalculator.SECONDS_PER_MINUTE) <= maxStayMinutes.getAsInt();
	}

	/** Whether the garage has duration bands: the banded form, or the grace period of a tiered garage. */
	public boolean hasBands() {
		return !bands.isEmpty();
	}

	private static void addIfPresent(List<String> names, String name, boolean present) {
		if (present) {
			names.add(name);
		}
	}

	private static void requireAtLeast(String garage, String field, OptionalLong value, long minimum) {
		if (value.isPresent() && value.getAsLong() < minimum) {
			throw new IllegalArgumentException(garage + field + " must be at least " + minimum + ", got " + value.getAsLong());
		}
	}

	private static void requireAtLeast(String garage, String field, OptionalInt value, int minimum) {
		if (value.isPresent() && value.getAsInt() < minimum) {
			throw new IllegalArgumentException(garage + field + " must be at least " + minimum + ", got " + value.getAsInt());
		}
	}

	private static void requireTiers(String garage, List<Tier> tiers) {
		List<int[]> covered = new ArrayList<>();
		for (Tier tier : tiers) {
			if (tier.start_s() < 0 || tier.unitMinutes() < 1 || tier.priceCents() < 1 || tier.end_s() < 1) {
				throw new IllegalArgumentException(garage + "tier " + tier + " must have a positive unit, a positive price, a"
						+ " positive end and a non-negative start");
			}
			if (tier.start_s() >= ParkingCostCalculator.SECONDS_PER_DAY || tier.end_s() > ParkingCostCalculator.SECONDS_PER_DAY
					|| tier.start_s() == tier.end_s()) {
				throw new IllegalArgumentException(garage + "tier [" + tier.start_s() + ", " + tier.end_s()
						+ ") s is no time-of-day interval of positive length");
			}
			if (tier.end_s() <= tier.start_s()) {
				covered.add(new int[] { tier.start_s(), ParkingCostCalculator.SECONDS_PER_DAY });
				covered.add(new int[] { 0, tier.end_s() });
			} else {
				covered.add(new int[] { tier.start_s(), tier.end_s() });
			}
		}
		if (tiers.stream().map(Tier::unitMinutes).distinct().count() != 1) {
			throw new IllegalArgumentException(garage + "the tiers must share one unit length (the units of a stay are counted"
					+ " from its arrival, P6)");
		}
		covered.sort((first, second) -> first[0] != second[0] ? Integer.compare(first[0], second[0])
				: Integer.compare(first[1], second[1]));
		for (int index = 1; index < covered.size(); index++) {
			if (covered.get(index)[0] < covered.get(index - 1)[1]) {
				throw new IllegalArgumentException(garage + "tiers overlap");
			}
		}
	}

	/**
	 * The bands of a tiered garage are its grace period: ONE closed free band (ASSUMPTION P10 for tiers); the tiers and a
	 * duration schedule exclude each other, and the free band is the only free stretch, so a tiered garage with a grace
	 * period has no first period.
	 */
	private static void requireGraceBand(String garage, List<Band> bands, OptionalInt firstPeriodMinutes) {
		requireBands(garage, bands);
		if (bands.size() != 1 || bands.get(0).kind() != BandKind.FREE || bands.get(0).toMinutes().isEmpty()) {
			throw new IllegalArgumentException(garage + "a tiered garage carries bands only as a grace period (one closed free"
					+ " band); tiers and a duration schedule exclude each other (one tariff structure per garage), found "
					+ bands.size() + " band(s) " + bands.stream().map(band -> band.kind().jsonName()).toList());
		}
		if (firstPeriodMinutes.isPresent()) {
			throw new IllegalArgumentException(garage + "a tiered garage with a grace period has no first period (the free"
					+ " band is its only duration band)");
		}
	}

	private static void requireBands(String garage, List<Band> bands) {
		OptionalInt previousTo = OptionalInt.of(0);
		for (int position = 0; position < bands.size(); position++) {
			Band band = bands.get(position);
			if (previousTo.isEmpty() || band.fromMinutes() != previousTo.getAsInt()) {
				throw new IllegalArgumentException(garage + "band " + position + " starts at " + band.fromMinutes()
						+ " min but the band before ends at " + (previousTo.isPresent() ? previousTo.getAsInt() : "infinity")
						+ "; bands are contiguous from 0 and only the last may be open-ended");
			}
			if (band.toMinutes().isPresent() && band.toMinutes().getAsInt() <= band.fromMinutes()) {
				throw new IllegalArgumentException(garage + "band " + position + " must end after it starts, got "
						+ band.fromMinutes() + "-" + band.toMinutes().getAsInt());
			}
			switch (band.kind()) {
			case FREE -> {
				if (band.priceCents() != 0 || band.unitMinutes().isPresent()) {
					throw new IllegalArgumentException(garage + "band " + position + " is free: price_cents must be 0 and"
							+ " unit_min null");
				}
			}
			case TOTAL -> {
				if (band.priceCents() < 1 || band.unitMinutes().isPresent()) {
					throw new IllegalArgumentException(garage + "band " + position + " is a total: price_cents must be positive"
							+ " and unit_min null");
				}
			}
			case INCREMENT -> {
				if (band.priceCents() < 1 || band.unitMinutes().isEmpty() || band.unitMinutes().getAsInt() < 1) {
					throw new IllegalArgumentException(garage + "band " + position + " is an increment: price_cents and"
							+ " unit_min must be positive");
				}
			}
			}
			previousTo = band.toMinutes();
		}
		if (bands.get(0).fromMinutes() != 0) {
			throw new IllegalArgumentException(garage + "the first band must start at 0 min");
		}
	}
}
