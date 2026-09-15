package org.eqasim.braunschweig.mode_choice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.scenario.ScenarioUtils;

public class BraunschweigModeAvailabilityTest {
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void passengerAvailabilityAllowsPassengerWithoutDriverCarAccess() {
		Person person = createPerson("none", "yes");
		person.getAttributes().putAttribute("carPassengerAvailability", "some");

		Collection<String> modes = new BraunschweigModeAvailability().getAvailableModes(person, List.of());

		assertTrue(modes.contains(BraunschweigModeChoiceModule.CAR_PASSENGER));
		assertFalse(modes.contains(TransportMode.car));
	}

	@Test
	public void passengerAvailabilityNoneDoesNotDisableLicensedDriver() {
		Person person = createPerson("all", "yes");
		person.getAttributes().putAttribute("carPassengerAvailability", "none");

		Collection<String> modes = availableModes(person);

		assertFalse(modes.contains(BraunschweigModeChoiceModule.CAR_PASSENGER));
		assertTrue(modes.contains(TransportMode.car));
	}

	@Test
	public void passengerAvailabilitySomeAndAllAllowPassengerWithoutLicense() {
		for (String availability : List.of("some", "all")) {
			Person person = createPerson("none", "no");
			person.getAttributes().putAttribute("carPassengerAvailability", availability);

			Collection<String> modes = availableModes(person);

			assertTrue(availability, modes.contains(BraunschweigModeChoiceModule.CAR_PASSENGER));
			assertFalse(availability, modes.contains(TransportMode.car));
		}
	}

	@Test
	public void missingPassengerAttributeUsesLegacyCarAndLicenseRules() {
		assertLegacyModes("none", "yes", false, false);
		assertLegacyModes("some", "yes", true, true);
		assertLegacyModes("all", "no", true, false);
		assertLegacyModes("none", "no", false, false);
	}

	@Test
	public void invalidPassengerAvailabilityStringFails() {
		Person person = createPerson("all", "yes");
		person.getAttributes().putAttribute("carPassengerAvailability", "sometimes");

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> availableModes(person));

		assertTrue(error.getMessage().contains("sometimes"));
		assertTrue(error.getMessage().contains("none, some, all"));
	}

	@Test
	public void nonStringPassengerAvailabilityFails() {
		Person person = createPerson("all", "yes");
		person.getAttributes().putAttribute("carPassengerAvailability", 1);

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> availableModes(person));

		assertTrue(error.getMessage().contains("java.lang.Integer"));
	}

	@Test
	public void nullPassengerAvailabilityFailsWhenAttributeIsPresent() {
		Person person = createPerson("all", "yes");
		person.getAttributes().putAttribute("carPassengerAvailability", null);

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> availableModes(person));

		assertTrue(error.getMessage().contains("null"));
	}

	@Test
	public void freightBypassesPassengerAvailability() {
		Person person = createPerson("all", "yes");
		person.getAttributes().putAttribute("carPassengerAvailability", "invalid-for-travelers");
		PopulationUtils.putSubpopulation(person, "freight");

		assertEquals(List.of("truck"), availableModes(person));
	}

	@Test
	public void passengerAvailabilityStringsSurvivePopulationXmlRoundTrip() {
		Scenario writtenScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		for (String availability : List.of("none", "some", "all")) {
			Person person = writtenScenario.getPopulation().getFactory().createPerson(Id.createPersonId(availability));
			person.getAttributes().putAttribute("carAvailability", "none");
			person.getAttributes().putAttribute("carPassengerAvailability", availability);
			PersonUtils.setLicence(person, "no");
			writtenScenario.getPopulation().addPerson(person);
		}

		String populationPath = temporaryFolder.getRoot().toPath().resolve("population.xml").toString();
		new PopulationWriter(writtenScenario.getPopulation()).write(populationPath);

		Scenario readScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new PopulationReader(readScenario).readFile(populationPath);

		assertFalse(availableModes(readScenario.getPopulation().getPersons().get(Id.createPersonId("none")))
				.contains(BraunschweigModeChoiceModule.CAR_PASSENGER));
		assertTrue(availableModes(readScenario.getPopulation().getPersons().get(Id.createPersonId("some")))
				.contains(BraunschweigModeChoiceModule.CAR_PASSENGER));
		assertTrue(availableModes(readScenario.getPopulation().getPersons().get(Id.createPersonId("all")))
				.contains(BraunschweigModeChoiceModule.CAR_PASSENGER));
	}

	@Test
	public void coverageLoggingEmitsOnlyOnFirstEvaluationOfEachPath() {
		Logger logger = (Logger) LogManager.getLogger(BraunschweigModeAvailability.class);
		CollectingAppender appender = new CollectingAppender();
		logger.addAppender(appender);
		appender.start();

		try {
			BraunschweigModeAvailability availability = new BraunschweigModeAvailability();
			availability.getAvailableModes(createPerson("all", "yes"), List.of());
			for (int index = 0; index < 3; index++) {
				Person person = createPerson("none", "no");
				person.getAttributes().putAttribute("carPassengerAvailability", "some");
				availability.getAvailableModes(person, List.of());
			}

			assertEquals(2, appender.messages.stream()
					.filter(message -> message.startsWith("carPassengerAvailability evaluations:"))
					.count());
		} finally {
			logger.removeAppender(appender);
			appender.stop();
		}
	}

	private void assertLegacyModes(String carAvailability, String license, boolean passengerExpected,
			boolean carExpected) {
		Collection<String> modes = availableModes(createPerson(carAvailability, license));
		assertEquals(passengerExpected, modes.contains(BraunschweigModeChoiceModule.CAR_PASSENGER));
		assertEquals(carExpected, modes.contains(TransportMode.car));
	}

	private Collection<String> availableModes(Person person) {
		return new BraunschweigModeAvailability().getAvailableModes(person, List.of());
	}

	private Person createPerson(String carAvailability, String license) {
		Person person = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation().getFactory()
				.createPerson(Id.createPersonId("person"));
		person.getAttributes().putAttribute("carAvailability", carAvailability);
		PersonUtils.setLicence(person, license);
		return person;
	}

	private static class CollectingAppender extends AbstractAppender {
		private final List<String> messages = new ArrayList<>();

		CollectingAppender() {
			super("passenger-availability-test", null, PatternLayout.createDefaultLayout(), false, null);
		}

		@Override
		public void append(LogEvent event) {
			messages.add(event.getMessage().getFormattedMessage());
		}
	}
}
