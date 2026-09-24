package org.eqasim.braunschweig.fares.zonal;

import java.util.Set;

/**
 * One priced PT trip: cash in euro cents, exactly one outcome label, for VRB tickets the VRB price
 * class (city, ps1..ps4), and whether the cash is a VRB short trip or single that counts towards the
 * day-ticket cap. The cash flag is carried separately from the label because a missing or unknown
 * ticket category keeps its own label but the VRB single price (ADR-0133 D8). Outcome labels are the
 * vocabulary of the per-iteration fare report and of the fallback share.
 */
public record FareQuote(long cents, String outcome, String priceClass, boolean vrbCash) {
	public static final String NO_PT_LEG = "no_pt_leg";
	public static final String CHILD_FREE = "child_free";
	public static final String VRB_SHORT_TRIP = "vrb_short_trip";
	public static final String VRB_SINGLE_ADULT = "vrb_single_adult";
	public static final String VRB_SINGLE_CHILD = "vrb_single_child";
	public static final String VRB_FLAT_NATIONAL = "vrb_flat_national";
	public static final String VRB_FLAT_REGIONAL = "vrb_flat_regional";
	public static final String EXTERNAL_NATIONAL_FLAT = "external_national_flat";
	public static final String EXTERNAL_RAIL_NT = "external_rail_nt";
	public static final String EXTERNAL_LOCAL_FLAT = "external_local_flat";
	public static final String CATEGORY_MISSING = "category_missing";
	public static final String CATEGORY_UNKNOWN = "category_unknown";
	public static final String LINE_SCOPE_MISSING = "line_scope_missing";
	/** A journey with a long-distance ride (DB Fernverkehr, Flix): the flat price of the fare model for everyone. */
	public static final String LONG_DISTANCE_FLAT = "long_distance_flat";
	public static final String VRB_PAIR_UNDEFINED_FALLBACK = "vrb_pair_undefined_fallback";
	public static final String EXTERNAL_RAIL_BEYOND_BANDS = "external_rail_beyond_bands";

	/** Informational label: the day-ticket cap reduced a trip's marginal cost. Not a quote outcome. */
	public static final String DAY_TICKET_CAP_APPLIED = "day_ticket_cap_applied";

	/** Labels counted for information only; they are excluded from the quote total of the fallback share. */
	public static final Set<String> INFORMATIONAL_LABELS = Set.of(DAY_TICKET_CAP_APPLIED);

	/** Outcomes whose input is incomplete or whose price is the configured fallback: the unsupported share. */
	public static final Set<String> FALLBACK_OUTCOMES = Set.of(CATEGORY_MISSING, CATEGORY_UNKNOWN, LINE_SCOPE_MISSING,
			VRB_PAIR_UNDEFINED_FALLBACK, EXTERNAL_RAIL_BEYOND_BANDS);

	/** Outcomes that are VRB tickets paid in cash; only these count towards the day-ticket cap. */
	public static final Set<String> VRB_CASH_OUTCOMES = Set.of(VRB_SHORT_TRIP, VRB_SINGLE_ADULT, VRB_SINGLE_CHILD);

	public FareQuote {
		if (cents < 0) {
			throw new IllegalArgumentException("fare cents must not be negative: " + cents);
		}
		if (outcome == null || outcome.isBlank()) {
			throw new IllegalArgumentException("fare outcome label is required");
		}
		if (vrbCash && priceClass == null) {
			throw new IllegalArgumentException("a VRB cash quote needs its price class for the day-ticket cap: " + outcome);
		}
	}

	/** Quote whose cash flag follows from its outcome label ({@link #VRB_CASH_OUTCOMES}). */
	public FareQuote(long cents, String outcome, String priceClass) {
		this(cents, outcome, priceClass, VRB_CASH_OUTCOMES.contains(outcome));
	}
}
