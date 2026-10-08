package org.eqasim.braunschweig.parking;

import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Pattern;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ReflectiveConfigGroup;

/**
 * MATSim config module "braunschweigParking": switches the car cost model to zone-based parking costs (design "parking
 * cost zones", eqasim-bs issue #436) and names the tariff model. Meant to be written into the prepared config by the
 * eqasim-bs preparation stage; absent or enabled=false keeps the existing car cost model. All prices, fee windows and
 * limits come from the tariff model JSON only; this module carries the switch, the path, the terminal-stay rule, the
 * minimum parked duration (assumption L1), which prices every car stay in a zone as lasting at least that long without
 * changing the simulated timing, and the search time factor of lever 4, which scales the parking search minutes of the
 * zone column into the car utility (0, the default, switches the term off).
 */
public final class ParkingConfigGroup extends ReflectiveConfigGroup {
	public static final String GROUP_NAME = "braunschweigParking";

	/**
	 * The largest minimum parked duration in minutes whose seconds fit the int that
	 * ParkingCostCalculator.minimumStayDeparture_s takes (about 68 years): a representation bound, not a plausibility
	 * limit.
	 */
	public static final int LARGEST_MINIMUM_STAY_MINUTES = Integer.MAX_VALUE / ParkingCostCalculator.SECONDS_PER_MINUTE;

	/**
	 * The largest search time factor: ten times the search minutes of the zone column in the car utility. A sanity bound,
	 * not a calibrated value; the sensitivity arm of the v2 design uses 1.0.
	 */
	public static final double MAXIMUM_SEARCH_TIME_FACTOR = 10.0;

	private static final Pattern DECIMAL_DIGITS = Pattern.compile("[0-9]+");

	/** Plain decimal digits with an optional fraction: no sign, no exponent, no blanks, no unit, no NaN or Infinity. */
	private static final Pattern PLAIN_DECIMAL = Pattern.compile("[0-9]+(\\.[0-9]+)?");

	private boolean enabled = false;
	private String tariffsPath;
	private String terminalStayRule = ParkingCostCalculator.TERMINAL_STAY_RULE_UNTIL_FEE_END;
	private int minimumStayMinutes = 0;
	private double searchTimeFactor = 0.0;

	public ParkingConfigGroup() {
		super(GROUP_NAME);
	}

	@StringGetter("enabled")
	public String getEnabled() {
		return Boolean.toString(enabled);
	}

	@StringSetter("enabled")
	public void setEnabled(String value) {
		enabled = literalBoolean("enabled", value);
	}

	public boolean isEnabled() {
		return enabled;
	}

	@StringGetter("tariffsPath")
	public String getTariffsPath() {
		return tariffsPath;
	}

	@StringSetter("tariffsPath")
	public void setTariffsPath(String value) {
		tariffsPath = value;
	}

	@StringGetter("terminalStayRule")
	public String getTerminalStayRule() {
		return terminalStayRule;
	}

	/** Only until_fee_end is implemented (assumption T1); any other rule fails instead of being ignored. */
	@StringSetter("terminalStayRule")
	public void setTerminalStayRule(String value) {
		if (!ParkingCostCalculator.TERMINAL_STAY_RULE_UNTIL_FEE_END.equals(value)) {
			throw new IllegalArgumentException(GROUP_NAME + ".terminalStayRule must be "
					+ ParkingCostCalculator.TERMINAL_STAY_RULE_UNTIL_FEE_END + " (the only implemented rule), got " + value);
		}
		terminalStayRule = value;
	}

	@StringGetter("minimumStayMinutes")
	public String getMinimumStayMinutes() {
		return Integer.toString(minimumStayMinutes);
	}

	/**
	 * Minimum parked duration in whole minutes (assumption L1), written by the eqasim-bs preparation stage as plain
	 * decimal digits: an integer in [0, {@value #LARGEST_MINIMUM_STAY_MINUTES}]. A sign, a fraction, blanks, a unit or a
	 * larger value fail instead of being rounded or ignored.
	 */
	@StringSetter("minimumStayMinutes")
	public void setMinimumStayMinutes(String value) {
		minimumStayMinutes = wholeMinutes("minimumStayMinutes", value);
	}

	/** Minimum parked duration in minutes (assumption L1); 0, the default when absent, prices as version 1. */
	public int minimumStayMinutes() {
		return minimumStayMinutes;
	}

	/** Minimum parked duration in seconds, as ParkingCostCalculator.minimumStayDeparture_s takes it. */
	public int minimumStay_s() {
		return minimumStayMinutes * ParkingCostCalculator.SECONDS_PER_MINUTE;
	}

	/** The factor as plain decimal text, never with an exponent, so that the written config reads back to the same value. */
	@StringGetter("searchTimeFactor")
	public String getSearchTimeFactor() {
		return BigDecimal.valueOf(searchTimeFactor).toPlainString();
	}

	/**
	 * Search time factor of lever 4, dimensionless, written by the eqasim-bs preparation stage as plain decimal text: digits
	 * with an optional fraction in [0, {@value #MAXIMUM_SEARCH_TIME_FACTOR}]. A sign, an exponent, blanks, a unit, a
	 * non-finite value or a larger value fail instead of being rounded or ignored.
	 */
	@StringSetter("searchTimeFactor")
	public void setSearchTimeFactor(String value) {
		searchTimeFactor = plainDecimal("searchTimeFactor", value, MAXIMUM_SEARCH_TIME_FACTOR);
	}

	/**
	 * Factor that scales the parking search minutes of the destination zone into the car utility
	 * ({@code betaTravelTime_u_min * factor * search_time_min}, lever 4); 0, the default when absent, leaves the utility as
	 * in version 1. A factor above 0 needs a zone with a search time in the tariff model and the global
	 * {@code car.constantParkingSearchPenalty_min} at 0; {@link ParkingSearchTimeCheck} stops the run at the controller
	 * start otherwise.
	 */
	public double searchTimeFactor() {
		return searchTimeFactor;
	}

	@Override
	public Map<String, String> getComments() {
		Map<String, String> comments = super.getComments();
		comments.put("enabled",
				"true switches the car cost model to zone-based parking costs (eqasim-bs issue #436); absent/false keeps the"
						+ " existing car cost model");
		comments.put("tariffsPath",
				"parking tariff model JSON, schema 1, 2 or 3 (the Python pipeline writes 3), written by the preparation stage"
						+ " (relative to the config file)");
		comments.put("terminalStayRule",
				"parking duration at the last activity of a plan; only until_fee_end: the car pays until the fee window of"
						+ " its arrival day ends (ASSUMPTION T1)");
		comments.put("minimumStayMinutes",
				"minimum parked duration in whole minutes (integer >= 0): every car stay in a parking zone is priced as"
						+ " lasting at least this long, before the tariff rules; the simulated timing is unchanged (ASSUMPTION"
						+ " L1). Absent: 0, the version 1 pricing");
		comments.put("searchTimeFactor",
				"factor (plain decimal in [0, 10]) on the parking search time of the destination zone in the car utility:"
						+ " betaTravelTime_u_min * factor * search_time_min of the tariff model (lever 4). A destination of"
						+ " purpose home gets no search time, whatever its zone (rule H1). Absent or 0: no term, the version 1"
						+ " utility. The run stops at startup (ParkingSearchTimeCheck) when the factor is above 0 and no zone of"
						+ " the tariff model carries a search_time_min above 0, or when car.constantParkingSearchPenalty_min is"
						+ " not 0 (the search time would be counted twice)");
		return comments;
	}

	/** Fail early, naming the parameter, when the module is enabled but incomplete. */
	public void requireSupported() {
		if (!enabled) {
			return;
		}
		if (tariffsPath == null || tariffsPath.isBlank()) {
			throw new IllegalArgumentException(GROUP_NAME + ".tariffsPath is required when enabled");
		}
	}

	/** The typed, enabled module, or null for an absent module or enabled=false. */
	public static ParkingConfigGroup active(Config config) {
		ConfigGroup group = config.getModules().get(GROUP_NAME);
		return group instanceof ParkingConfigGroup typed && typed.isEnabled() ? typed : null;
	}

	private static boolean literalBoolean(String name, String value) {
		if (!"true".equals(value) && !"false".equals(value)) {
			throw new IllegalArgumentException(GROUP_NAME + "." + name + " must be literal true or false, got " + value);
		}
		return Boolean.parseBoolean(value);
	}

	/** A plain decimal number from 0 to {@code largest}; anything else, a non-finite value included, fails. */
	private static double plainDecimal(String name, String value, double largest) {
		String problem = GROUP_NAME + "." + name + " must be a plain decimal number from 0 to " + largest
				+ " (digits with an optional fraction, no sign, exponent or unit), got '" + value + "'";
		if (value == null || !PLAIN_DECIMAL.matcher(value).matches()) {
			throw new IllegalArgumentException(problem);
		}
		double number = Double.parseDouble(value);
		if (!(number >= 0.0 && number <= largest)) {
			throw new IllegalArgumentException(problem);
		}
		return number;
	}

	/** Whole minutes from plain decimal digits, at most LARGEST_MINIMUM_STAY_MINUTES; anything else fails. */
	private static int wholeMinutes(String name, String value) {
		String problem = GROUP_NAME + "." + name + " must be a whole number of minutes from 0 to "
				+ LARGEST_MINIMUM_STAY_MINUTES + ", written as plain decimal digits, got '" + value + "'";
		if (value == null || !DECIMAL_DIGITS.matcher(value).matches()) {
			throw new IllegalArgumentException(problem);
		}
		int minutes;
		try {
			minutes = Integer.parseInt(value);
		} catch (NumberFormatException beyondTheIntRange) {
			throw new IllegalArgumentException(problem, beyondTheIntRange);
		}
		if (minutes > LARGEST_MINIMUM_STAY_MINUTES) {
			throw new IllegalArgumentException(problem);
		}
		return minutes;
	}
}
