package org.eqasim.braunschweig.fares.zonal;

import java.util.Map;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ReflectiveConfigGroup;

/**
 * MATSim config module "vrbFare": switches the PT cost model to the VRB zone tariff and names its
 * inputs (ADR-0133). Written into the prepared config by the eqasim-bs preparation stage; absent or
 * enabled=false keeps the legacy ring cost model. All prices, including the fallback price, come from
 * the fare model JSON only; this module carries switches, paths and the run guard. BraunschweigConfigurator
 * registers the group as optional, so EqasimConfigurator.updateConfig turns the generic XML module into it.
 */
public final class VrbFareConfigGroup extends ReflectiveConfigGroup {
	public static final String GROUP_NAME = "vrbFare";

	private boolean enabled = false;
	private String fareModelPath;
	private String lineScopesPath;
	private boolean dayTicketCapEnabled = true;
	private boolean longDistanceRoutingSurchargeEnabled = true;
	private double maximumUnsupportedShare = 0.05;

	public VrbFareConfigGroup() {
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

	@StringGetter("fareModelPath")
	public String getFareModelPath() {
		return fareModelPath;
	}

	@StringSetter("fareModelPath")
	public void setFareModelPath(String value) {
		fareModelPath = value;
	}

	@StringGetter("lineScopesPath")
	public String getLineScopesPath() {
		return lineScopesPath;
	}

	@StringSetter("lineScopesPath")
	public void setLineScopesPath(String value) {
		lineScopesPath = value;
	}

	@StringGetter("dayTicketCapEnabled")
	public String getDayTicketCapEnabled() {
		return Boolean.toString(dayTicketCapEnabled);
	}

	@StringSetter("dayTicketCapEnabled")
	public void setDayTicketCapEnabled(String value) {
		dayTicketCapEnabled = literalBoolean("dayTicketCapEnabled", value);
	}

	public boolean isDayTicketCapEnabled() {
		return dayTicketCapEnabled;
	}

	@StringGetter("longDistanceRoutingSurchargeEnabled")
	public String getLongDistanceRoutingSurchargeEnabled() {
		return Boolean.toString(longDistanceRoutingSurchargeEnabled);
	}

	@StringSetter("longDistanceRoutingSurchargeEnabled")
	public void setLongDistanceRoutingSurchargeEnabled(String value) {
		longDistanceRoutingSurchargeEnabled = literalBoolean("longDistanceRoutingSurchargeEnabled", value);
	}

	public boolean isLongDistanceRoutingSurchargeEnabled() {
		return longDistanceRoutingSurchargeEnabled;
	}

	@StringGetter("maximumUnsupportedShare")
	public String getMaximumUnsupportedShare() {
		return Double.toString(maximumUnsupportedShare);
	}

	@StringSetter("maximumUnsupportedShare")
	public void setMaximumUnsupportedShare(String value) {
		double parsed = Double.parseDouble(value);
		if (!(parsed >= 0.0 && parsed <= 1.0)) {
			throw new IllegalArgumentException("vrbFare.maximumUnsupportedShare must be within [0, 1], got " + value);
		}
		maximumUnsupportedShare = parsed;
	}

	public double maximumUnsupportedShare() {
		return maximumUnsupportedShare;
	}

	@Override
	public Map<String, String> getComments() {
		Map<String, String> comments = super.getComments();
		comments.put("enabled", "true switches the PT cost model to the VRB zone tariff; absent/false keeps the legacy ring model");
		comments.put("fareModelPath", "vrb_fare_model_2026.json written by the preparation stage (relative to the config file)");
		comments.put("lineScopesPath", "vrb_line_scopes.csv written by the preparation stage (relative to the config file)");
		comments.put("dayTicketCapEnabled",
				"price a person's VRB trips of a day at the cheapest of all singles or one day ticket plus the singles above its"
						+ " price class (ASSUMPTION, ADR-0133 D9)");
		comments.put("longDistanceRoutingSurchargeEnabled",
				"route PT with the long-distance flat price as extra in-vehicle cost, converted with the mode choice's value"
						+ " of time (ADR-0133 D6)");
		comments.put("maximumUnsupportedShare",
				"the run fails after an iteration whose fallback share exceeds this value (ASSUMPTION for diagnostics)");
		return comments;
	}

	/** Fail early, naming the parameter, when the module is enabled but incomplete. */
	public void requireSupported() {
		if (!enabled) {
			return;
		}
		if (fareModelPath == null || fareModelPath.isBlank()) {
			throw new IllegalArgumentException("vrbFare.fareModelPath is required when enabled");
		}
		if (lineScopesPath == null || lineScopesPath.isBlank()) {
			throw new IllegalArgumentException("vrbFare.lineScopesPath is required when enabled");
		}
	}

	/** The typed, enabled module, or null for an absent module or enabled=false. */
	public static VrbFareConfigGroup active(Config config) {
		ConfigGroup group = config.getModules().get(GROUP_NAME);
		return group instanceof VrbFareConfigGroup typed && typed.isEnabled() ? typed : null;
	}

	private static boolean literalBoolean(String name, String value) {
		if (!"true".equals(value) && !"false".equals(value)) {
			throw new IllegalArgumentException("vrbFare." + name + " must be literal true or false, got " + value);
		}
		return Boolean.parseBoolean(value);
	}
}
