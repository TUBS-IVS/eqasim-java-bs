package org.eqasim.core.scenario.cutter.population;

import org.matsim.api.core.v01.population.Activity;

/**
 * Merges consecutive outside activities like {@link DefaultMergeOutsideActivities} and passes every attribute of an
 * activity the merge removes (head loop, tail loop and middle loop) to the activity that absorbs it. The survivor's
 * own attributes win (put-if-absent), so e.g. the {@code portalGate} marker and {@code typeBeforeCutting} of a stay at
 * a hub survive on the merged activity (kept-stops mode, eqasim-bs#574, design R6).
 */
public class AttributeKeepingMergeOutsideActivities extends DefaultMergeOutsideActivities {
	@Override
	protected void absorb(Activity survivor, Activity removed) {
		removed.getAttributes().getAsMap().forEach((key, value) -> {
			if (survivor.getAttributes().getAttribute(key) == null) {
				survivor.getAttributes().putAttribute(key, value);
			}
		});
	}
}
