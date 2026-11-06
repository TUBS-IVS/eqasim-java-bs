package org.eqasim.scenario.cutter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eqasim.core.misc.Constants;
import org.eqasim.core.scenario.cutter.TransitCutSummary;
import org.eqasim.core.scenario.routing.PlanRouter;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;

/**
 * The transit cut summary joins the collapse marker of the cutter with the walk-fallback stamp of the router per trip
 * (eqasim-bs#574, design R12) and writes its metrics in a fixed order that the eqasim-bs prepare stage reads.
 */
public class TestTransitCutSummary {
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static Leg leg(PopulationFactory factory, String mode, String collapseCase, String walkFallbackFrom) {
		Leg leg = factory.createLeg(mode);

		if (collapseCase != null) {
			leg.getAttributes().putAttribute(Constants.CUT_TRANSIT_COLLAPSE_ATTRIBUTE, collapseCase);
		}

		if (walkFallbackFrom != null) {
			leg.getAttributes().putAttribute(PlanRouter.WALK_FALLBACK_ATTRIBUTE, walkFallbackFrom);
		}

		return leg;
	}

	/**
	 * One person, three pt trips: a collapsed one that walks, a collapsed one routed by pt and an unmarked walk
	 * fallback.
	 */
	private static Population createPopulation() {
		Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
		PopulationFactory factory = population.getFactory();

		Plan plan = factory.createPlan();
		plan.addActivity(factory.createActivityFromCoord("home", new Coord(0.0, 0.0)));
		plan.addLeg(leg(factory, TransportMode.walk, "inside_inside", "pt"));
		plan.addActivity(factory.createActivityFromCoord("work", new Coord(1000.0, 0.0)));
		plan.addLeg(leg(factory, TransportMode.walk, "inside_inside", null));
		plan.addActivity(factory.createActivityFromCoord("pt interaction", new Coord(1500.0, 0.0)));
		plan.addLeg(leg(factory, TransportMode.pt, "inside_inside", null));
		plan.addActivity(factory.createActivityFromCoord("pt interaction", new Coord(1800.0, 0.0)));
		plan.addLeg(leg(factory, TransportMode.walk, "inside_inside", null));
		plan.addActivity(factory.createActivityFromCoord("shop", new Coord(2000.0, 0.0)));
		plan.addLeg(leg(factory, TransportMode.walk, null, "pt"));
		plan.addActivity(factory.createActivityFromCoord("home", new Coord(0.0, 0.0)));

		Person person = factory.createPerson(Id.createPersonId("p"));
		person.addPlan(plan);
		population.addPerson(person);
		return population;
	}

	private static Map<String, Long> collapseCounts() {
		Map<String, Long> counts = new LinkedHashMap<>();
		counts.put("trips_with_crossings", 4L);
		counts.put("inside_inside", 2L);
		counts.put("outside_origin", 0L);
		counts.put("outside_destination", 0L);
		counts.put("through", 0L);
		return counts;
	}

	@Test
	public void collectJoinsTheMarkerWithTheWalkFallback() {
		TransitCutSummary summary = TransitCutSummary.collect(createPopulation(), collapseCounts(), 10, 2, 1, 5, 3);

		Assert.assertEquals(10, summary.stopsKeptInside());
		Assert.assertEquals(2, summary.stopsKeptHub());
		Assert.assertEquals(1, summary.stopsKeptOtherOutside());
		Assert.assertEquals(5, summary.routesKept());
		Assert.assertEquals(3, summary.routesDropped());
		Assert.assertEquals(4, summary.ptTripsWithCrossings());
		Assert.assertEquals(2, summary.collapsedInsideInside());
		Assert.assertEquals(1, summary.collapsedInsideInsideWalked());
		Assert.assertEquals(0, summary.collapsedOutsideOrigin());
		Assert.assertEquals(0, summary.collapsedOutsideOriginWalked());
		Assert.assertEquals(0, summary.collapsedOutsideDestination());
		Assert.assertEquals(0, summary.collapsedOutsideDestinationWalked());
		Assert.assertEquals(0, summary.collapsedThrough());
		Assert.assertEquals(0, summary.collapsedThroughWalked());
	}

	@Test
	public void writeProducesEveryMetricInTheFixedOrder() throws IOException {
		TransitCutSummary summary = TransitCutSummary.collect(createPopulation(), collapseCounts(), 10, 2, 1, 5, 3);
		File csv = new File(folder.getRoot(), "bs_transit_cut_summary.csv");
		summary.write(csv.toPath());

		String expected = "metric,value\n" //
				+ "stops_kept_inside,10\n" //
				+ "stops_kept_hub,2\n" //
				+ "stops_kept_other_outside,1\n" //
				+ "routes_kept,5\n" //
				+ "routes_dropped,3\n" //
				+ "pt_trips_with_crossings,4\n" //
				+ "collapsed_inside_inside,2\n" //
				+ "collapsed_inside_inside_walked,1\n" //
				+ "collapsed_outside_origin,0\n" //
				+ "collapsed_outside_origin_walked,0\n" //
				+ "collapsed_outside_destination,0\n" //
				+ "collapsed_outside_destination_walked,0\n" //
				+ "collapsed_through,0\n" //
				+ "collapsed_through_walked,0\n";
		Assert.assertEquals(expected, new String(Files.readAllBytes(csv.toPath()), StandardCharsets.UTF_8));
	}

	@Test
	public void ratesAreFormattedWithTheirDenominator() {
		Assert.assertEquals("1/4 (25.0 %)", TransitCutSummary.rate(1, 4));
		Assert.assertEquals("0/0 (0.0 %)", TransitCutSummary.rate(0, 0));
	}
}
