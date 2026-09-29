package org.eqasim.braunschweig.parking;

import java.util.Map;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ReflectiveConfigGroup;

/**
 * MATSim config module "braunschweigParking": switches the car cost model to zone-based parking costs (design "parking
 * cost zones", eqasim-bs issue #436) and names the tariff model. Meant to be written into the prepared config by the
 * eqasim-bs preparation stage; absent or enabled=false keeps the existing car cost model. All prices, fee windows and
 * limits come from the tariff model JSON only; this module carries the switch, the path and the terminal-stay rule.
 */
public final class ParkingConfigGroup extends ReflectiveConfigGroup {
	public static final String GROUP_NAME = "braunschweigParking";

	private boolean enabled = false;
	private String tariffsPath;
	private String terminalStayRule = ParkingCostCalculator.TERMINAL_STAY_RULE_UNTIL_FEE_END;

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
}
