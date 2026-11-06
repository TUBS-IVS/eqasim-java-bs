package org.eqasim.scenario.cutter.transit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.eqasim.core.scenario.cutter.extent.ScenarioExtent;
import org.eqasim.core.scenario.cutter.transit.TransitHubStops;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.TransitScheduleFactoryImpl;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

public class TestTransitHubStops {
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void resolvesEveryFacilityWithTheExactName() {
		TransitSchedule schedule = schedule();

		Set<Id<TransitStopFacility>> hubs = TransitHubStops.resolve(schedule, List.of("Hannover Hbf"),
				insideNothing());

		Assert.assertEquals(Set.of(id("hbf1"), id("hbf2")), hubs);
	}

	@Test
	public void matchesCaseSensitivelyAndNotByPrefix() {
		TransitSchedule schedule = schedule();

		try {
			TransitHubStops.resolve(schedule, List.of("hannover hbf", "Hannover"), insideNothing());
			Assert.fail("a name that is not the exact facility name must not match");
		} catch (IllegalArgumentException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("hannover hbf"));
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("Hannover"));
		}
	}

	@Test
	public void failsNamingEveryUnmatchedName() {
		TransitSchedule schedule = schedule();

		try {
			TransitHubStops.resolve(schedule, List.of("Hannover Hbf", "Lehrte", "Celle"), insideNothing());
			Assert.fail("unmatched hub names must fail the resolution");
		} catch (IllegalArgumentException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("Lehrte"));
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("Celle"));
			Assert.assertFalse(e.getMessage(), e.getMessage().contains("Hannover Hbf"));
		}
	}

	@Test
	public void warnsForAHubInsideTheExtent() {
		TransitSchedule schedule = schedule();
		// hbf1 at x = 1 lies inside, hbf2 at x = 2 outside
		ScenarioExtent extent = extentWithInsideX(1.0);

		List<String> warnings = new ArrayList<>();
		AbstractAppender appender = new AbstractAppender("hubWarnings", null, null, true, Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				if (event.getLevel() == Level.WARN) {
					warnings.add(event.getMessage().getFormattedMessage());
				}
			}
		};
		appender.start();
		LoggerContext context = (LoggerContext) LogManager.getContext(false);
		org.apache.logging.log4j.core.Logger logger = context.getLogger(TransitHubStops.class.getName());
		logger.addAppender(appender);

		Set<Id<TransitStopFacility>> hubs;
		try {
			hubs = TransitHubStops.resolve(schedule, List.of("Hannover Hbf"), extent);
		} finally {
			logger.removeAppender(appender);
			appender.stop();
		}

		// the hub is still resolved; exactly the facility inside the extent is warned about
		Assert.assertEquals(Set.of(id("hbf1"), id("hbf2")), hubs);
		Assert.assertEquals(warnings.toString(), 1, warnings.size());
		Assert.assertTrue(warnings.get(0), warnings.get(0).contains("hbf1"));
	}

	@Test
	public void readNamesTrimsAndDropsBlankLines() throws IOException {
		Path file = temporaryFolder.newFile("hubs.txt").toPath();
		Files.write(file, " Göttingen \n\nHannover Hbf\n   \n".getBytes(StandardCharsets.UTF_8));

		Assert.assertEquals(List.of("Göttingen", "Hannover Hbf"), TransitHubStops.readNames(file));
	}

	private static Id<TransitStopFacility> id(String id) {
		return Id.create(id, TransitStopFacility.class);
	}

	private static TransitSchedule schedule() {
		TransitScheduleFactory factory = new TransitScheduleFactoryImpl();
		TransitSchedule schedule = factory.createTransitSchedule();
		addFacility(factory, schedule, "hbf1", "Hannover Hbf", 1.0);
		addFacility(factory, schedule, "hbf2", "Hannover Hbf", 2.0);
		addFacility(factory, schedule, "zob", "Hannover Hauptbahnhof/ZOB", 3.0);
		addFacility(factory, schedule, "unnamed", null, 4.0);
		return schedule;
	}

	private static void addFacility(TransitScheduleFactory factory, TransitSchedule schedule, String id, String name,
			double x) {
		TransitStopFacility facility = factory.createTransitStopFacility(id(id), new Coord(x, 0.0), false);
		facility.setName(name);
		schedule.addStopFacility(facility);
	}

	private static ScenarioExtent insideNothing() {
		return extentWithInsideX(Double.NaN);
	}

	private static ScenarioExtent extentWithInsideX(double insideX) {
		return new ScenarioExtent() {
			@Override
			public boolean isInside(Coord coord) {
				return coord.getX() == insideX;
			}

			@Override
			public List<Coord> computeEuclideanIntersections(Coord from, Coord to) {
				throw new IllegalStateException("not needed");
			}

			@Override
			public Coord getInteriorPoint() {
				throw new IllegalStateException("not needed");
			}
		};
	}
}
