package org.eqasim.core.standalone_mode_choice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceModel;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.DefaultTripCandidate;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup.CompressionType;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * The standalone mode choice leaves every person of an excluded subpopulation untouched and still chooses for all
 * other persons (eqasim-bs ADR-0182: the in-commuters keep their synthesised mode; the in-loop strategies give them
 * KeepLastSelected + ReRoute only). A tour of an in-commuter without an {@code outside} activity (home > work > home
 * inside the simulated area: 4,907 persons of the 100 % population of eqasim-bs run prod100_20261031) passes eqasim's
 * OutsideFilter, so only a subpopulation exclusion keeps its mode.
 */
public class StandaloneModeChoiceExcludedSubpopulationsTest {
	private static final String CHOSEN_MODE = TransportMode.bike;

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	/** A model that chooses {@link #CHOSEN_MODE} for every trip, so a re-chosen trip is visible in its leg mode. */
	private static final class ConstantModeModel implements DiscreteModeChoiceModel {
		@Override
		public List<TripCandidate> chooseModes(Person person, List<DiscreteModeChoiceTrip> trips,
				java.util.Random random) {
			List<TripCandidate> candidates = new ArrayList<>();
			for (int index = 0; index < trips.size(); index++) {
				candidates.add(new DefaultTripCandidate(0.0, CHOSEN_MODE, 0.0));
			}
			return candidates;
		}
	}

	private static void addPerson(Population population, String id, String subpopulation) {
		PopulationFactory factory = population.getFactory();
		Person person = factory.createPerson(Id.createPersonId(id));
		if (subpopulation != null) {
			PopulationUtils.putSubpopulation(person, subpopulation);
		}
		Plan plan = factory.createPlan();
		plan.addActivity(withEnd(factory.createActivityFromCoord("home", new Coord(0.0, 0.0)), 8.0 * 3600.0));
		plan.addLeg(factory.createLeg(TransportMode.car));
		plan.addActivity(withEnd(factory.createActivityFromCoord("work", new Coord(5000.0, 0.0)), 17.0 * 3600.0));
		plan.addLeg(factory.createLeg(TransportMode.car));
		plan.addActivity(factory.createActivityFromCoord("home", new Coord(0.0, 0.0)));
		person.addPlan(plan);
		population.addPerson(person);
	}

	private static org.matsim.api.core.v01.population.Activity withEnd(
			org.matsim.api.core.v01.population.Activity activity, double endTime) {
		activity.setEndTime(endTime);
		return activity;
	}

	private static List<String> modes(Population population, String id) {
		List<String> modes = new ArrayList<>();
		for (Leg leg : TripStructureUtils.getLegs(population.getPersons().get(Id.createPersonId(id)).getSelectedPlan())) {
			modes.add(leg.getMode());
		}
		return modes;
	}

	private Scenario scenario() {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		Population population = scenario.getPopulation();
		addPerson(population, "resident", null);
		addPerson(population, "incommuter", "incommuter");
		addPerson(population, "student_incommuter", "student_incommuter");
		return scenario;
	}

	private void perform(Scenario scenario, String excludedSubpopulations, int threads) throws Exception {
		StandaloneModeChoiceConfigGroup configGroup = new StandaloneModeChoiceConfigGroup();
		configGroup.setExcludedSubpopulations(excludedSubpopulations);
		String output = new File(temporaryFolder.getRoot(), "output_" + threads).getAbsolutePath();
		OutputDirectoryHierarchy hierarchy = new OutputDirectoryHierarchy(output, null,
				OverwriteFileSetting.deleteDirectoryIfExists, CompressionType.gzip);
		new StandaloneModeChoicePerformer(ConstantModeModel::new, configGroup, scenario.getPopulation(), threads, 1L,
				hierarchy, scenario).run();
	}

	@Test
	public void excludedSubpopulationsKeepTheirModesWhileResidentsAreChosen() throws Exception {
		for (int threads : new int[] { 0, 2 }) {
			Scenario scenario = scenario();
			perform(scenario, "incommuter, student_incommuter", threads);
			Population population = scenario.getPopulation();

			assertEquals("threads " + threads, List.of(CHOSEN_MODE, CHOSEN_MODE), modes(population, "resident"));
			assertEquals("threads " + threads, List.of(TransportMode.car, TransportMode.car),
					modes(population, "incommuter"));
			assertEquals("threads " + threads, List.of(TransportMode.car, TransportMode.car),
					modes(population, "student_incommuter"));
			assertEquals(3, population.getPersons().size());
		}
	}

	@Test
	public void withoutExclusionEveryPersonIsChosen() throws Exception {
		Scenario scenario = scenario();
		perform(scenario, "", 0);
		for (String id : List.of("resident", "incommuter", "student_incommuter")) {
			assertEquals(id, List.of(CHOSEN_MODE, CHOSEN_MODE), modes(scenario.getPopulation(), id));
		}
	}

	@Test
	public void anExclusionMatchingNobodyFails() {
		// The eqasim-bs #566 failure: the subpopulation label was lost upstream, so the exclusion would silently
		// exclude nobody and every in-commuter would be re-chosen.
		Scenario scenario = scenario();
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> perform(scenario, "pendler", 0));
		assertEquals(true, error.getMessage().contains("pendler"));
	}

	@Test
	public void theListIsParsedTrimmedAndWithoutEmptyEntries() {
		StandaloneModeChoiceConfigGroup configGroup = new StandaloneModeChoiceConfigGroup();
		assertEquals(Set.of(), configGroup.getExcludedSubpopulationSet());
		configGroup.setExcludedSubpopulations(" incommuter ,student_incommuter,, ");
		assertEquals(Set.of("incommuter", "student_incommuter"), configGroup.getExcludedSubpopulationSet());
		assertEquals("incommuter,student_incommuter", configGroup.getExcludedSubpopulations());
	}
}
