package org.eqasim.braunschweig.parking;

import java.util.Map;
import java.util.regex.Pattern;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ReflectiveConfigGroup;

/**
 * MATSim config module "braunschweigParking": switches the car cost model to zone-based parking costs (design "parking
 * cost zones", eqasim-bs issue #436) and names the tariff model. Meant to be written into the prepared config by the
 * eqasim-bs preparation stage; absent or enabled=false keeps the existing car cost model. All prices, fee windows and
 * limits come from the tariff model JSON only; this module carries the switch, the path, the terminal-stay rule and the
 * minimum parked duration (assumption L1), which prices every car stay in a zone as lasting at least that long without
 * changing the simulated timing.
 */
public final class ParkingConfigGroup extends ReflectiveConfigGroup {
	public static final String GROUP_NAME = "braunschweigParking";

	/**
	 * The largest minimum parked duration in minutes whose seconds fit the int that
	 * ParkingCostCalculator.minimumStayDeparture_s takes (about 68 years): a representation bound, not a plausibility
	 * limit.
	 */
	public static final int LARGEST_MINIMUM_STAY_MINUTES = Integer.MAX_VALUE / ParkingCostCalculator.SECONDS_PER_MINUTE;

	private static final Pattern DECIMAL_DIGITS = Pattern.compile("[0-9]+");

	private boolean enabled = false;
	private String tariffsPath;
	private String terminalStayRule = ParkingCostCalculator.TERMINAL_STAY_RULE_UNTIL_FEE_END;
	private int minimumStayMinutes = 0;

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

	@Override
	public Map<String, String> getComments() {
		Map<String, String> comments = super.getComments();
		comments.put("enabled",
				"true switches the car cost model to zone-based parking costs (eqasim-bs issue #436); absent/false keeps the"
						+ " existing car cost model");
		comments.put("tariffsPath",
				"parking tariff model JSON (schema 1) written by the preparation stage (relative to the config file)");
		comments.put("terminalStayRule",
				"parking duration at the last activity of a plan; only until_fee_end: the car pays until the fee window of"
						+ " its arrival day ends (ASSUMPTION T1)");
		comments.put("minimumStayMinutes",
				"minimum parked duration in whole minutes (integer >= 0): every car stay in a parking zone is priced as"
						+ " lasting at least this long, before the tariff rules; the simulated timing is unchanged (ASSUMPTION"
						+ " L1). Absent: 0, the version 1 pricing");
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
