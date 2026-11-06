package org.eqasim.scenario.cutter.population;

import java.util.ArrayList;
import java.util.List;

import org.eqasim.core.scenario.cutter.population.AttributeKeepingMergeOutsideActivities;
import org.eqasim.core.scenario.cutter.population.DefaultMergeOutsideActivities;
import org.junit.Assert;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.population.PopulationUtils;

/**
 * The attribute-keeping merge passes the attributes of every outside activity it removes to the activity that absorbs
 * it, with put-if-absent semantics (eqasim-bs#574, design R6). The default merge drops them (pin of upstream).
 */
public class TestAttributeKeepingMergeOutsideActivities {
	private static Activity activity(String type, double x, double endTime, Object... attributes) {
		Activity activity = PopulationUtils.createActivityFromCoord(type, new Coord(x, 0.0));
		activity.setEndTime(endTime);

		for (int i = 0; i < attributes.length; i += 2) {
			activity.getAttributes().putAttribute((String) attributes[i], attributes[i + 1]);
		}

		return activity;
	}

	private static List<PlanElement> plan(Object... elements) {
		List<PlanElement> plan = new ArrayList<>();

		for (Object element : elements) {
			plan.add(element instanceof String mode ? PopulationUtils.createLeg(mode) : (PlanElement) element);
		}

		return plan;
	}

	/** home, pt, outside A (no attributes), outside, stay (portalGate), outside, outside C, pt, home. */
	private static List<PlanElement> middlePlan() {
		return plan(activity("home", 0.0, 100.0), TransportMode.pt, activity("outside", 1.0, 200.0), "outside",
				activity("outside", 2.0, 500.0, "portalGate", true), "outside", activity("outside", 3.0, 600.0),
				TransportMode.pt, activity("home", 0.0, 900.0));
	}

	@Test
	public void theMiddleOfThreeOutsideActivitiesPassesItsAttributesToTheFirst() {
		List<PlanElement> plan = middlePlan();
		new AttributeKeepingMergeOutsideActivities().run(plan);

		Assert.assertEquals(7, plan.size());
		Activity survivor = (Activity) plan.get(2);
		Assert.assertEquals(new Coord(1.0, 0.0), survivor.getCoord());
		Assert.assertEquals(true, survivor.getAttributes().getAttribute("portalGate"));
		Assert.assertEquals(500.0, survivor.getEndTime().seconds(), 1e-6);
	}

	@Test
	public void theDefaultMergeDropsTheAttributes() {
		List<PlanElement> plan = middlePlan();
		new DefaultMergeOutsideActivities().run(plan);

		Assert.assertEquals(7, plan.size());
		Activity survivor = (Activity) plan.get(2);
		Assert.assertNull(survivor.getAttributes().getAttribute("portalGate"));
		Assert.assertEquals(500.0, survivor.getEndTime().seconds(), 1e-6);
	}

	@Test
	public void theHeadLoopPassesTheFirstActivitysAttributesToTheSecond() {
		List<PlanElement> plan = plan(activity("outside", 1.0, 100.0, "a", 1), "outside",
				activity("outside", 2.0, 200.0), TransportMode.pt, activity("home", 0.0, 900.0));
		new AttributeKeepingMergeOutsideActivities().run(plan);

		Assert.assertEquals(3, plan.size());
		Activity survivor = (Activity) plan.get(0);
		Assert.assertEquals(new Coord(2.0, 0.0), survivor.getCoord());
		Assert.assertEquals(1, survivor.getAttributes().getAttribute("a"));
	}

	@Test
	public void theTailLoopPassesTheLastActivitysAttributesToTheOneBefore() {
		List<PlanElement> plan = plan(activity("home", 0.0, 100.0), TransportMode.pt, activity("outside", 1.0, 200.0),
				"outside", activity("outside", 2.0, 300.0, "b", 2));
		new AttributeKeepingMergeOutsideActivities().run(plan);

		Assert.assertEquals(3, plan.size());
		Activity survivor = (Activity) plan.get(2);
		Assert.assertEquals(new Coord(1.0, 0.0), survivor.getCoord());
		Assert.assertEquals(2, survivor.getAttributes().getAttribute("b"));
	}

	@Test
	public void theSurvivorsOwnAttributeWins() {
		List<PlanElement> plan = plan(activity("outside", 1.0, 100.0, "a", 1, "c", 3), "outside",
				activity("outside", 2.0, 200.0, "a", 2), TransportMode.pt, activity("home", 0.0, 900.0));
		new AttributeKeepingMergeOutsideActivities().run(plan);

		Activity survivor = (Activity) plan.get(0);
		Assert.assertEquals(2, survivor.getAttributes().getAttribute("a"));
		Assert.assertEquals(3, survivor.getAttributes().getAttribute("c"));
	}
}
