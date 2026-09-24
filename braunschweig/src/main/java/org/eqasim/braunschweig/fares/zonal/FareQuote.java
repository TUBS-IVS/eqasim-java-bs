package org.eqasim.braunschweig.fares.zonal;

import java.util.Set;

/**
 * One priced PT trip: cash in euro cents, exactly one outcome label and, for VRB tickets, the
 * VRB price class (city, ps1..ps4). Outcome labels are the vocabulary of the per-iteration fare
 * report and of the fallback share (ADR-0133 D8).
 */
public record FareQuote(long cents, String outcome, String priceClass) {
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
	public static final String LONG_DISTANCE_FALLBACK = "long_distance_fallback";
	public static final String VRB_PAIR_UNDEFINED_FALLBACK = "vrb_pair_undefined_fallback";
	public static final String EXTERNAL_RAIL_BEYOND_BANDS = "external_rail_beyond_bands";

	/** Outcomes whose input is incomplete or whose price is the configured fallback: the unsupported share. */
	public static final Set<String> FALLBACK_OUTCOMES = Set.of(CATEGORY_MISSING, CATEGORY_UNKNOWN, LINE_SCOPE_MISSING,
			LONG_DISTANCE_FALLBACK, VRB_PAIR_UNDEFINED_FALLBACK, EXTERNAL_RAIL_BEYOND_BANDS);

	public FareQuote {
		if (cents < 0) {
			throw new IllegalArgumentException("fare cents must not be negative: " + cents);
		}
		if (outcome == null || outcome.isBlank()) {
			throw new IllegalArgumentException("fare outcome label is required");
		}
	}

	/** True for VRB tickets paid in cash (short trip and singles); only these count towards the day-ticket cap. */
	public boolean isVrbCash() {
		return VRB_SHORT_TRIP.equals(outcome) || VRB_SINGLE_ADULT.equals(outcome) || VRB_SINGLE_CHILD.equals(outcome);
	}
}
