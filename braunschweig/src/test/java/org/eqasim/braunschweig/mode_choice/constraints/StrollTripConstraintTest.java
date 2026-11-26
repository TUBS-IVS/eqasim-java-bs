package org.eqasim.braunschweig.mode_choice.constraints;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.eqasim.braunschweig.mode_choice.BraunschweigModeChoiceModule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripConstraint;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

/**
 * Pins the rule for trips into a stroll activity (eqasim-bs#679): only a destination activity whose attribute
 * {@code outAndBackStroll} is the Boolean {@code true} limits the trip to the modes of the same-facility leisure rule.
 */
public class StrollTripConstraintTest {
	private static final List<String> ALL_MODES = List.of(TransportMode.car, BraunschweigModeChoiceModule.CAR_PASSENGER,
			TransportMode.pt, TransportMode.walk, BraunschweigModeChoiceModule.BICYCLE);

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void marked_trip_rejects_car_and_accepts_walk_and_bicycle() {
		DiscreteModeChoiceTrip trip = trip(Boolean.TRUE);
		TripConstraint constraint = createConstraint(trip);

		for (String mode : ALL_MODES) {
			assertEquals(mode, SameFacilityLeisureTripConstraint.ALLOWED_MODES.contains(mode),
					constraint.validateBeforeEstimation(trip, mode, List.of()));
		}

		assertFalse(constraint.validateBeforeEstimation(trip, TransportMode.car, List.of()));
		assertTrue(constraint.validateBeforeEstimation(trip, TransportMode.walk, List.of()));
		assertTrue(constraint.validateBeforeEstimation(trip, BraunschweigModeChoiceModule.BICYCLE, List.of()));
	}

	@Test
	public void unmarked_trip_accepts_every_mode() {
		assertEveryModeAllowed(trip(null));
	}

	@Test
	public void boolean_false_and_absent_attribute_place_no_restriction() {
		assertEveryModeAllowed(trip(Boolean.FALSE));
		assertEveryModeAllowed(trip(null));
	}

	@Test
	public void string_true_is_a_format_error() {
		DiscreteModeChoiceTrip trip = trip("true");

		try {
			StrollTripConstraint.appliesTo(person(), trip);
			fail("a String attribute value must be rejected");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("person"));
			assertTrue(e.getMessage(), e.getMessage().contains("'leisure'"));
			assertTrue(e.getMessage(), e.getMessage().contains("'true'"));
			assertTrue(e.getMessage(), e.getMessage().contains("java.lang.String"));
		}

		// The constraint itself fails the same way, so the mode choice does not run with the rule switched off.
		try {
			createConstraint(trip);
			fail("the factory must reject the plan");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("java.lang.String"));
		}
	}

	@Test
	public void counts_caught_and_evaluated_trips() {
		StrollTripConstraint.Factory factory = new StrollTripConstraint.Factory();
		DiscreteModeChoiceTrip marked = trip(Boolean.TRUE);
		DiscreteModeChoiceTrip unmarked = trip(null);
		TripConstraint constraint = factory.createConstraint(person(), List.of(marked, unmarked), ALL_MODES);

		assertEquals(2, factory.getTrips());
		assertEquals(1, factory.getCaughtTrips());
		assertEquals(0, factory.getReachedTrips());

		constraint.validateBeforeEstimation(marked, TransportMode.car, List.of());
		constraint.validateBeforeEstimation(marked, TransportMode.walk, List.of());

		assertEquals(1, factory.getReachedTrips());
		assertEquals(1, factory.getRejectedCandidates());
	}

	/**
	 * Pins the Boolean round trip: the attribute in the form the Python writer emits, read back by MATSim's population
	 * reader, is a {@code Boolean} the rule recognises; an activity without the attribute is not caught.
	 */
	@Test
	public void attribute_read_from_population_xml_is_caught() throws Exception {
		String xml = """
				<?xml version="1.0" encoding="utf-8"?>
				<!DOCTYPE population SYSTEM "http://www.matsim.org/files/dtd/population_v6.dtd">
				<population>
				<person id="p">
				<plan selected="yes">
				<activity type="home" x="0" y="0" end_time="08:00:00" />
				<leg mode="car" />
				<activity type="leisure" x="10" y="0" end_time="09:00:00">
				<attributes>
				<attribute name="outAndBackStroll" class="java.lang.Boolean">true</attribute>
				</attributes>
				</activity>
				<leg mode="car" />
				<activity type="leisure" x="20" y="0" end_time="10:00:00" />
				</plan>
				</person>
				</population>
				""".stripLeading();
		File file = folder.newFile("population.xml");
		Files.writeString(file.toPath(), xml, StandardCharsets.UTF_8);

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new PopulationReader(scenario).readFile(file.getAbsolutePath());
		List<org.matsim.api.core.v01.population.PlanElement> elements = scenario.getPopulation().getPersons()
				.get(Id.createPersonId("p")).getSelectedPlan().getPlanElements();
		Activity home = (Activity) elements.get(0);
		Activity marked = (Activity) elements.get(2);
		Activity unmarked = (Activity) elements.get(4);

		assertEquals(Boolean.TRUE, marked.getAttributes().getAttribute(StrollTripConstraint.OUT_AND_BACK_STROLL_ATTRIBUTE));
		assertTrue(StrollTripConstraint.appliesTo(person(),
				new DiscreteModeChoiceTrip(home, marked, "car", List.of(), 0, 0, 0, new AttributesImpl())));
		assertFalse(StrollTripConstraint.appliesTo(person(),
				new DiscreteModeChoiceTrip(marked, unmarked, "car", List.of(), 0, 1, 1, new AttributesImpl())));
	}

	private static void assertEveryModeAllowed(DiscreteModeChoiceTrip trip) {
		TripConstraint constraint = createConstraint(trip);

		for (String mode : ALL_MODES) {
			assertTrue(mode, constraint.validateBeforeEstimation(trip, mode, List.of()));
		}
	}

	private static TripConstraint createConstraint(DiscreteModeChoiceTrip trip) {
		return new StrollTripConstraint.Factory().createConstraint(person(), List.of(trip), ALL_MODES);
	}

	private static Person person() {
		return PopulationUtils.getFactory().createPerson(Id.createPersonId("person"));
	}

	/** A trip home to leisure; the out-and-back stroll attribute of the destination is set when the value is not null. */
	private static DiscreteModeChoiceTrip trip(Object strollValue) {
		Activity origin = PopulationUtils.createActivityFromCoord("home", new Coord(0, 0));
		Activity destination = PopulationUtils.createActivityFromCoord("leisure", new Coord(100, 0));

		if (strollValue != null) {
			destination.getAttributes().putAttribute(StrollTripConstraint.OUT_AND_BACK_STROLL_ATTRIBUTE, strollValue);
		}

		return new DiscreteModeChoiceTrip(origin, destination, TransportMode.car, List.of(), 0, 0, 0,
				new AttributesImpl());
	}
}
