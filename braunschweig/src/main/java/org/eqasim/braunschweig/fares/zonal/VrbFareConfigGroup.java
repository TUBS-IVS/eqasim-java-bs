package org.eqasim.braunschweig.fares.zonal;

import java.util.Map;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ReflectiveConfigGroup;

/**
 * MATSim config module "vrbFare": switches the PT cost model to the VRB zone tariff and names its
 * inputs (ADR-0133). Written into the prepared config by the eqasim-bs preparation stage; absent or
 * enabled=false keeps the legacy ring cost model.
 */
public final class VrbFareConfigGroup extends ReflectiveConfigGroup {
	public static final String GROUP_NAME = "vrbFare";

	private boolean enabled = false;
	private String fareModelPath;
	private String lineScopesPath;
	private boolean dayTicketCapEnabled = true;
	private long unsupportedFallbackPriceCents = 370;
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

	@StringGetter("unsupportedFallbackPriceCents")
	public String getUnsupportedFallbackPriceCents() {
		return Long.toString(unsupportedFallbackPriceCents);
	}

	@StringSetter("unsupportedFallbackPriceCents")
	public void setUnsupportedFallbackPriceCents(String value) {
		long parsed = Long.parseLong(value);
		if (parsed < 0) {
			throw new IllegalArgumentException("vrbFare.unsupportedFallbackPriceCents must be >= 0, got " + value);
		}
		unsupportedFallbackPriceCents = parsed;
	}

	public long unsupportedFallbackPriceCents() {
		return unsupportedFallbackPriceCents;
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
				"cap a person's daily VRB cash at the day ticket of the highest price class used (ASSUMPTION, ADR-0133 D9)");
		comments.put("unsupportedFallbackPriceCents", "price in euro cents of a counted fallback outcome (ASSUMPTION)");
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

	/** A config read from XML carries the module as a generic group; replace it by the typed one. */
	public static void promoteIfPresent(Config config) {
		ConfigGroup raw = config.getModules().get(GROUP_NAME);
		if (raw != null && !(raw instanceof VrbFareConfigGroup)) {
			VrbFareConfigGroup typed = new VrbFareConfigGroup();
			for (Map.Entry<String, String> parameter : raw.getParams().entrySet()) {
				typed.addParam(parameter.getKey(), parameter.getValue());
			}
			config.removeModule(GROUP_NAME);
			config.addModule(typed);
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
