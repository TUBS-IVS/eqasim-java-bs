package org.matsim.analysis;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eqasim.braunschweig.analysis.LoopTripDistances;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.config.groups.ControllerConfigGroup.CompressionType;
import org.matsim.core.config.groups.GlobalConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.TripStructureUtils;

/**
 * Without any loop trip, {@link LoopTripDistances} must give the values of MATSim's own {@link TravelDistanceStats}
 * and {@link PKMbyModeCalculator} on the same executed plans (eqasim-bs#679). Placed in MATSim's package because both
 * MATSim classes have package-private constructors; a change of MATSim's definitions in a later MATSim version makes
 * this test fail instead of letting the companion files drift silently. The plans hold the cases where the definitions
 * differ between the two MATSim statistics: a leg without a route and a route without a distance (NaN).
 */
public class LoopTripDistancesMatsimParityTest {
	private static final Id<Link> LINK = Id.createLinkId("l");

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static void addLeg(PopulationFactory factory, Plan plan, String mode, String routingMode, Double meters) {
		Leg leg = factory.createLeg(mode);
		TripStructureUtils.setRoutingMode(leg, routingMode);
		if (meters != null) {
			leg.setRoute(RouteUtils.createGenericRouteImpl(LINK, LINK));
			if (!meters.isNaN()) {
				leg.getRoute().setDistance(meters);
			}
		}
		plan.addLeg(leg);
	}

	private static void addActivity(PopulationFactory factory, Plan plan, String type) {
		plan.addActivity(factory.createActivityFromCoord(type, new Coord(0.0, 0.0)));
	}

	private static Map<Id<Person>, Plan> executedPlans() {
		PopulationFactory factory = PopulationUtils.createPopulation(ConfigUtils.createConfig()).getFactory();
		Map<Id<Person>, Plan> plans = new HashMap<>();

		Plan first = factory.createPlan();
		addActivity(factory, first, "home");
		addLeg(factory, first, "walk", "walk", 1200.0);
		addActivity(factory, first, "work");
		addLeg(factory, first, "car", "car", 15000.0);
		addActivity(factory, first, "home");
		plans.put(Id.createPersonId(1), first);

		Plan second = factory.createPlan();
		addActivity(factory, second, "home");
		addLeg(factory, second, "pt", "pt", null); // no route
		addActivity(factory, second, "shop");
		addLeg(factory, second, "walk", "walk", Double.NaN); // a route without a distance
		addActivity(factory, second, "leisure");
		addLeg(factory, second, "bike", "bike", 3300.0);
		addActivity(factory, second, "home");
		plans.put(Id.createPersonId(2), second);

		Plan third = factory.createPlan();
		addActivity(factory, third, "home");
		addLeg(factory, third, "walk", "car", 50.0);
		addActivity(factory, third, "car interaction");
		addLeg(factory, third, "car", "car", 8000.0);
		addActivity(factory, third, "car interaction");
		addLeg(factory, third, "walk", "car", 70.0);
		addActivity(factory, third, "work");
		plans.put(Id.createPersonId(3), third);
		return plans;
	}

	@Test
	public void withoutLoopTripsTheCompanionGivesMatsimsValues() throws Exception {
		Map<Id<Person>, Plan> plans = executedPlans();
		File output = folder.newFolder("output");
		OutputDirectoryHierarchy io = new OutputDirectoryHierarchy(output.getPath(),
				OverwriteFileSetting.overwriteExistingFiles, CompressionType.none);
		GlobalConfigGroup global = new GlobalConfigGroup();

		TravelDistanceStats distances = new TravelDistanceStats(new ControllerConfigGroup(), io, global);
		distances.addIteration(0, plans);
		distances.writeOutput(0, false);
		distances.close();
		PKMbyModeCalculator kilometres = new PKMbyModeCalculator(io, global);
		kilometres.addIteration(0, plans);
		kilometres.writeOutput(false);

		LoopTripDistances.Result result = LoopTripDistances.compute(plans, personId -> null);

		String[] matsimDistances = Files.readAllLines(new File(output, "traveldistancestats.csv").toPath(),
				StandardCharsets.UTF_8).get(1).split(";");
		assertEquals(Double.parseDouble(matsimDistances[1]), result.legStatistics.getAverage(), 1e-9);
		assertEquals(Double.parseDouble(matsimDistances[2]), result.tripStatistics.getAverage(), 1e-9);

		List<String> matsimKilometres = Files.readAllLines(new File(output, "pkm_modestats.csv").toPath(),
				StandardCharsets.UTF_8);
		String[] modes = matsimKilometres.get(0).split(";");
		String[] values = matsimKilometres.get(1).split(";");
		Map<String, Long> matsimByMode = new HashMap<>();
		for (int column = 1; column < modes.length; column++) {
			matsimByMode.put(modes[column], Long.parseLong(values[column]));
		}
		Map<String, Long> companionByMode = new HashMap<>();
		result.metersByMode.forEach((mode, meters) -> companionByMode.put(mode, Math.round(meters / 1000.0)));
		// pt appears with 0 km in both: its leg has no route, which counts 0 there.
		assertEquals(matsimByMode, companionByMode);
		assertEquals(0L, (long) companionByMode.get("pt"));
	}
}
