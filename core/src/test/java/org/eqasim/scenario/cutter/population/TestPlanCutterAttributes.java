package org.eqasim.scenario.cutter.population;

import java.util.ArrayList;
import java.util.List;

import org.eqasim.core.misc.Constants;
import org.eqasim.core.scenario.cutter.extent.CircularScenarioExtent;
import org.eqasim.core.scenario.cutter.extent.ScenarioExtent;
import org.eqasim.core.scenario.cutter.population.PlanCutter;
import org.eqasim.core.scenario.cutter.population.trips.TeleportationTripProcessor;
import org.eqasim.core.scenario.cutter.population.trips.crossing.teleportation.DefaultTeleportationCrossingPointFinder;
import org.junit.Assert;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;

/**
 * The plan cutter replaces an activity outside the extent by a fresh outside activity. In the kept-stops mode it
 * copies the replaced activity's attributes onto it, so that e.g. the portal gate stay keeps its {@code portalGate}
 * marker (eqasim-bs#574, design R6); in the extent mode only {@code typeBeforeCutting} is set, as upstream.
 */
public class TestPlanCutterAttributes {
	private final ScenarioExtent extent = new CircularScenarioExtent(new Coord(0.0, 0.0), 10000.0);

	private PlanCutter createCutter(boolean copyActivityAttributes) {
		TeleportationTripProcessor processor = new TeleportationTripProcessor(
				new DefaultTeleportationCrossingPointFinder(extent), extent);
		// No merge, so the replaced activity stays visible
		return new PlanCutter(processor, extent, plan -> {
		}, copyActivityAttributes);
	}

	private static Leg walk(double departureTime) {
		Leg leg = PopulationUtils.createLeg(TransportMode.walk);
		TripStructureUtils.setRoutingMode(leg, TransportMode.walk);
		leg.setDepartureTime(departureTime);
		leg.setTravelTime(3600.0);
		return leg;
	}

	/** Home inside, a stay outside the extent typed outside and marked as a portal gate, home inside. */
	private static List<PlanElement> createPlan() {
		Activity home = PopulationUtils.createActivityFromCoord("home", new Coord(100.0, 0.0));
		home.setEndTime(8.0 * 3600.0);

		Activity stay = PopulationUtils.createActivityFromCoord("outside", new Coord(20000.0, 0.0));
		stay.setEndTime(12.0 * 3600.0);
		stay.getAttributes().putAttribute("portalGate", true);

		Activity back = PopulationUtils.createActivityFromCoord("home", new Coord(100.0, 0.0));

		List<PlanElement> elements = new ArrayList<>();
		elements.add(home);
		elements.add(walk(8.0 * 3600.0));
		elements.add(stay);
		elements.add(walk(12.0 * 3600.0));
		elements.add(back);
		return elements;
	}

	private static Activity replacedStay(List<PlanElement> result) {
		for (PlanElement element : result) {
			if (element instanceof Activity activity && activity.getCoord().equals(new Coord(20000.0, 0.0))) {
				return activity;
			}
		}

		throw new AssertionError("The replaced stay is missing from the cut plan");
	}

	@Test
	public void theReplacedActivityKeepsItsAttributes() {
		Activity stay = replacedStay(createCutter(true).processPlan(Id.createPersonId("p"), createPlan()));

		Assert.assertEquals("outside", stay.getType());
		Assert.assertEquals(true, stay.getAttributes().getAttribute("portalGate"));
		Assert.assertEquals("outside", stay.getAttributes().getAttribute(Constants.TYPE_BEFORE_CUTTING_ATTRIBUTE));
		Assert.assertEquals(12.0 * 3600.0, stay.getEndTime().seconds(), 1e-6);
	}

	@Test
	public void withoutCopyingOnlyTheTypeBeforeCuttingIsSet() {
		Activity stay = replacedStay(createCutter(false).processPlan(Id.createPersonId("p"), createPlan()));

		Assert.assertNull(stay.getAttributes().getAttribute("portalGate"));
		Assert.assertEquals("outside", stay.getAttributes().getAttribute(Constants.TYPE_BEFORE_CUTTING_ATTRIBUTE));
		Assert.assertEquals(1, stay.getAttributes().size());
	}
}
