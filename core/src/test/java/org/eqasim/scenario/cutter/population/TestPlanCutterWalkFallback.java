package org.eqasim.scenario.cutter.population;

import java.util.ArrayList;
import java.util.List;

import org.eqasim.core.scenario.cutter.extent.CircularScenarioExtent;
import org.eqasim.core.scenario.cutter.extent.ScenarioExtent;
import org.eqasim.core.scenario.cutter.population.DefaultMergeOutsideActivities;
import org.eqasim.core.scenario.cutter.population.PlanCutter;
import org.eqasim.core.scenario.cutter.population.trips.TeleportationTripProcessor;
import org.eqasim.core.scenario.cutter.population.trips.crossing.teleportation.DefaultTeleportationCrossingPointFinder;
import org.eqasim.core.scenario.routing.PlanRouter;
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
 * Verifies that the plan cutter keeps the walk-fallback stamp of a trip that
 * the plan router had replaced by a walk (eqasim-bs#487): the cutter rebuilds
 * every trip from fresh legs, which used to drop the stamp, so the plan check
 * downstream could not count the planned-pt trips realised as walks.
 */
public class TestPlanCutterWalkFallback {
	private final ScenarioExtent extent = new CircularScenarioExtent(new Coord(0.0, 0.0), 10000.0);

	private PlanCutter createCutter() {
		TeleportationTripProcessor processor = new TeleportationTripProcessor(
				new DefaultTeleportationCrossingPointFinder(extent), extent);
		return new PlanCutter(processor, extent, new DefaultMergeOutsideActivities());
	}

	private List<PlanElement> createWalkTrip(Coord destination, String walkFallbackFrom) {
		Activity home = PopulationUtils.createActivityFromCoord("home", new Coord(100.0, 0.0));
		home.setEndTime(20.0 * 3600.0);

		Leg leg = PopulationUtils.createLeg(TransportMode.walk);
		TripStructureUtils.setRoutingMode(leg, TransportMode.walk);
		leg.setDepartureTime(20.0 * 3600.0);
		leg.setTravelTime(3600.0);
		if (walkFallbackFrom != null) {
			leg.getAttributes().putAttribute(PlanRouter.WALK_FALLBACK_ATTRIBUTE, walkFallbackFrom);
		}

		Activity work = PopulationUtils.createActivityFromCoord("work", destination);

		List<PlanElement> elements = new ArrayList<>();
		elements.add(home);
		elements.add(leg);
		elements.add(work);
		return elements;
	}

	@Test
	public void stampOfAnInsideWalkFallbackTripIsKept() {
		List<PlanElement> result = createCutter().processPlan(Id.createPersonId("p"),
				createWalkTrip(new Coord(4900.0, 0.0), "pt"));

		List<Leg> legs = TripStructureUtils.getLegs(result);
		Assert.assertEquals(1, legs.size());
		Assert.assertEquals(TransportMode.walk, legs.get(0).getMode());
		Assert.assertEquals("pt", legs.get(0).getAttributes().getAttribute(PlanRouter.WALK_FALLBACK_ATTRIBUTE));
	}

	@Test
	public void stampOfACrossingWalkFallbackTripIsKeptOnTheInsideLeg() {
		// The trip leaves the extent: the inside part ends at an outside activity at
		// the crossing point, the outside part is merged away
		List<PlanElement> result = createCutter().processPlan(Id.createPersonId("p"),
				createWalkTrip(new Coord(20000.0, 0.0), "pt"));

		List<Leg> legs = TripStructureUtils.getLegs(result);
		Assert.assertEquals(1, legs.size());
		Assert.assertEquals(TransportMode.walk, legs.get(0).getMode());
		Assert.assertEquals("pt", legs.get(0).getAttributes().getAttribute(PlanRouter.WALK_FALLBACK_ATTRIBUTE));
		Assert.assertEquals("outside", ((Activity) result.get(result.size() - 1)).getType());
	}

	@Test
	public void outsideLegOfAnIncomingWalkFallbackTripIsNotStamped() {
		// Origin outside, destination inside: an outside leg to the crossing point, then the walk
		List<PlanElement> elements = createWalkTrip(new Coord(4900.0, 0.0), "pt");
		Activity origin = PopulationUtils.createActivityFromCoord("home", new Coord(-20000.0, 0.0));
		origin.setEndTime(20.0 * 3600.0);
		elements.set(0, origin);
		elements.add(PopulationUtils.createLeg(TransportMode.walk));
		elements.add(PopulationUtils.createActivityFromCoord("home", new Coord(-20000.0, 0.0)));
		((Activity) elements.get(2)).setEndTime(22.0 * 3600.0);
		Leg back = (Leg) elements.get(3);
		TripStructureUtils.setRoutingMode(back, TransportMode.walk);
		back.setDepartureTime(22.0 * 3600.0);
		back.setTravelTime(3600.0);

		List<PlanElement> result = createCutter().processPlan(Id.createPersonId("p"), elements);

		int stamped = 0;
		for (Leg leg : TripStructureUtils.getLegs(result)) {
			Object stamp = leg.getAttributes().getAttribute(PlanRouter.WALK_FALLBACK_ATTRIBUTE);
			if (leg.getMode().equals("outside")) {
				Assert.assertNull(stamp);
			} else if (stamp != null) {
				stamped++;
			}
		}
		// Only the stamped outbound trip, never the unstamped return
		Assert.assertEquals(1, stamped);
	}

	@Test
	public void plainWalkTripStaysUnstamped() {
		List<PlanElement> result = createCutter().processPlan(Id.createPersonId("p"),
				createWalkTrip(new Coord(4900.0, 0.0), null));

		for (Leg leg : TripStructureUtils.getLegs(result)) {
			Assert.assertNull(leg.getAttributes().getAttribute(PlanRouter.WALK_FALLBACK_ATTRIBUTE));
		}
	}
}
