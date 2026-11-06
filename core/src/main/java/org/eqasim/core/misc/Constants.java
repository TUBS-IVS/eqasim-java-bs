package org.eqasim.core.misc;

public class Constants {
	// Population
	public final static String OUTSIDE_MODE = "outside";
	public final static String OUTSIDE_ACTIVITY_TYPE = "outside";
	public final static String OUTSIDE_AGENT_ATTRIBUTE = "isOutside";
	public final static String TYPE_BEFORE_CUTTING_ATTRIBUTE = "typeBeforeCutting";

	/**
	 * String leg attribute that the scenario cutter (kept-stops mode) puts on every leg of the routing mode it emits
	 * for a pt trip whose crossing points it collapsed; the value is the case: {@code inside_inside},
	 * {@code outside_origin}, {@code outside_destination} or {@code through} (eqasim-bs#574).
	 */
	public final static String CUT_TRANSIT_COLLAPSE_ATTRIBUTE = "cutTransitCollapse";

	/**
	 * String activity attribute that the scenario cutter (kept-stops mode) puts on every {@code outside} activity it
	 * creates at a public-transport stop (an in-vehicle crossing or a walk transition at a kept stop); the value is the
	 * id of the stop facility. The outside activity adapter anchors such an activity at the stop's coordinate with one
	 * facility per stop instead of at the midpoint of the nearest road link (eqasim-bs#574).
	 */
	public final static String CUT_TRANSIT_STOP_ATTRIBUTE = "cutTransitStopId";

	/**
	 * How the scenario cutter cuts pt trips (eqasim-bs#574). {@code EXTENT} (option value {@code extent}, the
	 * default) cuts at every crossing of the extent polygon, as upstream eqasim does. {@code KEPT_STOPS} (option value
	 * {@code kept-stops}) cuts at the boundary of the kept timetable (stops inside the extent plus hub anchors),
	 * collapses the excursions of a trip to at most one outside activity per outside end, marks the collapsed trips
	 * and keeps activity attributes through the cut.
	 */
	public enum TransitCutMode {
		EXTENT("extent"), KEPT_STOPS("kept-stops");

		private final String option;

		TransitCutMode(String option) {
			this.option = option;
		}

		public String getOption() {
			return option;
		}

		/** The mode of a command-line value; fails with an IllegalArgumentException naming the valid values. */
		public static TransitCutMode fromOption(String value) {
			for (TransitCutMode mode : values()) {
				if (mode.option.equals(value)) {
					return mode;
				}
			}

			throw new IllegalArgumentException(
					"Unknown transit cut mode '" + value + "'; expected 'extent' or 'kept-stops'");
		}
	}
}
