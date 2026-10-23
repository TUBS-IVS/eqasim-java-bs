package org.eqasim.core.tools.routing;

import java.util.ArrayList;
import java.util.List;

import org.eqasim.core.tools.routing.BatchPublicTransportRouter.LegRole;
import org.junit.Assert;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.population.PopulationUtils;

/**
 * Verifies that the batch public transport router classifies the walk legs of a
 * routed trip by their position among the legs, so that interaction activities
 * between the legs do not turn the egress walk into a transfer walk.
 */
public class BatchPublicTransportRouterLegRoleTest {
	private static List<PlanElement> trip(boolean withInteractions, String... modes) {
		List<PlanElement> elements = new ArrayList<>();
		for (int index = 0; index < modes.length; index++) {
			if (withInteractions && index > 0) {
				elements.add(PopulationUtils.createStageActivityFromCoordLinkIdAndModePrefix(new Coord(0.0, 0.0),
						Id.createLinkId("l"), "pt"));
			}
			elements.add(PopulationUtils.createLeg(modes[index]));
		}
		return elements;
	}

	@Test
	public void lastWalkIsEgressWhenInteractionActivitiesSeparateTheLegs() {
		Assert.assertEquals(List.of(LegRole.ACCESS_WALK, LegRole.VEHICLE, LegRole.EGRESS_WALK),
				BatchPublicTransportRouter.classifyLegs(trip(true, "walk", "pt", "walk")));
	}

	@Test
	public void middleWalkIsTransferAndLastWalkIsEgressWithInteractionActivities() {
		Assert.assertEquals(
				List.of(LegRole.ACCESS_WALK, LegRole.VEHICLE, LegRole.TRANSFER_WALK, LegRole.VEHICLE,
						LegRole.EGRESS_WALK),
				BatchPublicTransportRouter.classifyLegs(trip(true, "walk", "pt", "walk", "pt", "walk")));
	}

	@Test
	public void legsOnlyOutputIsClassifiedTheSameWay() {
		// SwissRailRaptor without intermodal access returns legs only
		Assert.assertEquals(
				List.of(LegRole.ACCESS_WALK, LegRole.VEHICLE, LegRole.TRANSFER_WALK, LegRole.VEHICLE,
						LegRole.EGRESS_WALK),
				BatchPublicTransportRouter.classifyLegs(trip(false, "walk", "pt", "walk", "pt", "walk")));
	}

	@Test
	public void singleDirectWalkIsAccess() {
		Assert.assertEquals(List.of(LegRole.ACCESS_WALK),
				BatchPublicTransportRouter.classifyLegs(trip(false, "walk")));
	}
}
