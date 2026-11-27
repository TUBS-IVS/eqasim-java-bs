package org.eqasim.braunschweig.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup.CompressionType;
import org.matsim.core.config.groups.GlobalConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.scoring.ExperiencedPlansService;

/**
 * Pins the loop trip distances (eqasim-bs#679): MATSim's distance statistics with the survey diary length of the trips
 * whose destination activity carries {@code loopTripDiaryDistanceKm}; every expected value is computed by hand.
 */
public class LoopTripDistancesTest {
	private static final Id<Link> LINK = Id.createLinkId("l");
	private static final String KM = LoopTripDistances.LOOP_TRIP_DIARY_DISTANCE_ATTRIBUTE;

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/** One leg: mode, routing mode and route distance in metres (NaN: no route). */
	private record LegSpec(String mode, String routingMode, double meters) {
	}

	private static Plan plan(PopulationFactory factory, List<?> elements) {
		Plan plan = factory.createPlan();
		for (Object element : elements) {
			if (element instanceof String type) {
				plan.addActivity(factory.createActivityFromCoord(type, new Coord(0.0, 0.0)));
			} else if (element instanceof Double km) {
				// a diary length for the activity added just before
				((Activity) plan.getPlanElements().get(plan.getPlanElements().size() - 1)).getAttributes()
						.putAttribute(KM, km);
			} else {
				LegSpec spec = (LegSpec) element;
				Leg leg = factory.createLeg(spec.mode());
				TripStructureUtils.setRoutingMode(leg, spec.routingMode());
				if (!Double.isNaN(spec.meters())) {
					leg.setRoute(RouteUtils.createGenericRouteImpl(LINK, LINK));
					leg.getRoute().setDistance(spec.meters());
				}
				plan.addLeg(leg);
			}
		}
		return plan;
	}

	private static LegSpec leg(String mode, double meters) {
		return new LegSpec(mode, mode, meters);
	}

	private static Population population() {
		return PopulationUtils.createPopulation(ConfigUtils.createConfig());
	}

	/**
	 * Person 1: work, a stroll from work (diary 3.0 km), back to work (diary 0.8 km), home by car (5 km); person 2:
	 * home to work by car (5 km).
	 */
	private static Map<Id<Person>, Plan> executedPlans(PopulationFactory factory) {
		return Map.of(
				Id.createPersonId(1), plan(factory, List.of("work", leg("walk", 0.0), "leisure", leg("walk", 0.0), "work",
						leg("car", 5000.0), "home")),
				Id.createPersonId(2), plan(factory, List.of("home", leg("car", 5000.0), "work")));
	}

	private static Map<Id<Person>, Plan> selectedPlans(PopulationFactory factory) {
		return Map.of(
				Id.createPersonId(1), plan(factory, List.of("work", leg("walk", 0.0), "leisure", 3.0, leg("walk", 0.0),
						"work", 0.8, leg("car", 5000.0), "home")),
				Id.createPersonId(2), plan(factory, List.of("home", leg("car", 5000.0), "work")));
	}

	@Test
	public void zeroLengthLoopTripsCountTheirDiaryLength() {
		PopulationFactory factory = population().getFactory();
		Map<Id<Person>, Plan> selected = selectedPlans(factory);

		LoopTripDistances.Result result = LoopTripDistances.compute(executedPlans(factory), selected::get);

		// trips (and legs, one per trip): 3000, 800, 5000, 5000 m
		assertEquals(4, result.tripStatistics.getCount());
		assertEquals(13800.0 / 4.0, result.tripStatistics.getAverage(), 1e-9);
		assertEquals(13800.0 / 4.0, result.legStatistics.getAverage(), 1e-9);
		assertEquals(Map.of("walk", 3800.0, "car", 10000.0), result.metersByMode);
		assertEquals(2, result.correctedTrips);
		assertEquals(3800.0, result.correctedTripDiaryMeters, 1e-9);
		assertEquals(0.0, result.correctedTripRouteMeters, 1e-9);
		assertEquals(0, result.mismatchedTrips);
	}

	@Test
	public void theMainLegTakesTheDiaryLengthAndTheTripsLegsAddUpToIt() {
		PopulationFactory factory = population().getFactory();
		// A bicycle loop with walk legs to and from the bicycle (5 m each), routing mode bicycle; diary 2.0 km.
		List<?> legs = List.of(new LegSpec("walk", "bicycle", 5.0), "bicycle interaction",
				new LegSpec("bicycle", "bicycle", 0.0), "bicycle interaction", new LegSpec("walk", "bicycle", 5.0));
		Plan executed = plan(factory, concat(List.of("home"), legs, List.of("leisure")));
		Plan selected = plan(factory, concat(List.of("home"), legs, List.of("leisure", 2.0)));

		LoopTripDistances.Result result = LoopTripDistances.compute(Map.of(Id.createPersonId(1), executed),
				id -> selected);

		assertEquals(2000.0, result.tripStatistics.getSum(), 1e-9);
		assertEquals(Map.of("walk", 10.0, "bicycle", 1990.0), result.metersByMode);
		assertEquals(3, result.legStatistics.getCount());
		assertEquals(10.0, result.correctedTripRouteMeters, 1e-9);
	}

	@Test
	public void aTripThatDoesNotMatchTheSelectedPlanKeepsItsRouteLengthAndIsCounted() {
		PopulationFactory factory = population().getFactory();
		Plan executed = plan(factory, List.of("home", leg("walk", 0.0), "leisure"));
		Plan selected = plan(factory, List.of("home", leg("walk", 0.0), "shop", 3.0));

		LoopTripDistances.Result result = LoopTripDistances.compute(Map.of(Id.createPersonId(1), executed),
				id -> selected);

		assertEquals(0, result.correctedTrips);
		assertEquals(1, result.mismatchedTrips);
		assertEquals(0.0, result.tripStatistics.getSum(), 1e-9);
	}

	@Test
	public void aDiaryLengthThatIsNoPositiveDoubleThrows() {
		PopulationFactory factory = population().getFactory();
		Plan executed = plan(factory, List.of("home", leg("walk", 0.0), "leisure"));
		Plan selected = plan(factory, List.of("home", leg("walk", 0.0), "leisure"));
		((Activity) selected.getPlanElements().get(2)).getAttributes().putAttribute(KM, "3.0");

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> LoopTripDistances.compute(Map.of(Id.createPersonId(1), executed), id -> selected));
		assertTrue(error.getMessage().contains(KM));
	}

	@Test
	public void theListenerWritesBothFilesForEveryIteration() throws Exception {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		PopulationFactory factory = scenario.getPopulation().getFactory();
		selectedPlans(factory).forEach((id, plan) -> {
			Person person = factory.createPerson(id);
			person.addPlan(plan);
			person.setSelectedPlan(plan);
			scenario.getPopulation().addPerson(person);
		});
		Map<Id<Person>, Plan> executed = executedPlans(factory);
		File output = folder.newFolder("output");
		LoopTripDistanceStatsListener listener = new LoopTripDistanceStatsListener(new FixedExperiencedPlans(executed),
				scenario, new OutputDirectoryHierarchy(output.getPath(), OverwriteFileSetting.overwriteExistingFiles,
						CompressionType.none),
				new GlobalConfigGroup());

		listener.notifyIterationEnds(new IterationEndsEvent(null, 0, false));
		listener.notifyIterationEnds(new IterationEndsEvent(null, 1, true));

		List<String> distances = Files.readAllLines(
				new File(output, LoopTripDistanceStatsListener.TRAVEL_DISTANCE_FILE).toPath(), StandardCharsets.UTF_8);
		assertEquals(List.of("ITERATION;avg. Average Leg distance;avg. Average Trip distance", "0;3450.0;3450.0",
				"1;3450.0;3450.0"), distances);
		List<String> kilometres = Files.readAllLines(new File(output, LoopTripDistanceStatsListener.PKM_FILE).toPath(),
				StandardCharsets.UTF_8);
		// 3.8 km on foot rounds to 4, like MATSim's own file
		assertEquals(List.of("Iteration;car;walk", "0;10;4", "1;10;4"), kilometres);
	}

	private static List<Object> concat(List<?> first, List<?> second, List<?> third) {
		List<Object> all = new java.util.ArrayList<>(first);
		all.addAll(second);
		all.addAll(third);
		return all;
	}

	/** Returns the same executed plans in every iteration. */
	private static final class FixedExperiencedPlans implements ExperiencedPlansService {
		private final Map<Id<Person>, Plan> plans;

		FixedExperiencedPlans(Map<Id<Person>, Plan> plans) {
			this.plans = plans;
		}

		@Override
		public void writeExperiencedPlans(String filename) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Map<Id<Person>, Plan> getExperiencedPlans() {
			return plans;
		}

		@Override
		public Population getPopulationWithExperiencedPlans() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void finishIteration() {
		}
	}
}
