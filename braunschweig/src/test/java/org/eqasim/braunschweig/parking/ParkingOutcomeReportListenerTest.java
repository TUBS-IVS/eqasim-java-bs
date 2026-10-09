package org.eqasim.braunschweig.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.core.config.groups.ControllerConfigGroup.CompressionType;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.StartupEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Per-iteration parking outcome report: {@code ITERS/it.N/N.parking_outcomes.csv} (CSV v2 of the v2 spec amendment A5:
 * one row per non-zero cell of outcome, zone and purpose), one {@code [parking]} log line with the shares of the outcomes
 * that occurred and one with the share of the priced stays the minimum parked duration extended (assumption L1), whose
 * active value is logged once at startup. Version 1 only reports: no threshold, no failure.
 */
public class ParkingOutcomeReportListenerTest {
	private static final String V3_HEADER = "outcome,zone_id,purpose,count,share,garage_probability_sum,"
			+ "stays_with_garages_in_range";

	/**
	 * The fixture tariff model with the garage options OFF (garage_decay_m and garage_decay_commute_m 0): three zones carry a
	 * search time (fx_bs_ia_v2, fx_bs_ib_v2, fx_wob_v2), and the report has no garage option line.
	 */
	private static ParkingTariffs tariffsWithSearchTimes;

	/**
	 * The fixture tariff model as written, with the garage options ON (garage_decay_m 400, garage_decay_commute_m 250, the 19
	 * fixture garages).
	 */
	private static ParkingTariffs tariffsWithGarageOptions;

	/** The schema-1 fixture tariff model of version 1: no zone carries a search time. */
	private static ParkingTariffs tariffsWithoutSearchTimes;

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@BeforeClass
	public static void readTariffs() throws Exception {
		tariffsWithGarageOptions = read("/parking/parking_tariffs_fixture.json");
		ObjectNode optionsOff = (ObjectNode) new ObjectMapper().readTree(Path.of(ParkingOutcomeReportListenerTest.class
				.getResource("/parking/parking_tariffs_fixture.json").toURI()).toFile());
		optionsOff.put("garage_decay_m", 0.0);
		optionsOff.put("garage_decay_commute_m", 0.0);
		tariffsWithSearchTimes = ParkingTariffs.parse(optionsOff);
		tariffsWithoutSearchTimes = read(ParkingCostCalculatorTest.SCHEMA_1_TARIFFS_RESOURCE);
	}

	private static ParkingTariffs read(String resourceName) throws Exception {
		URL resource = ParkingOutcomeReportListenerTest.class.getResource(resourceName);
		assertNotNull("test resource " + resourceName + " is missing", resource);
		return ParkingTariffs.read(Path.of(resource.toURI()));
	}

	private OutputDirectoryHierarchy output() {
		return new OutputDirectoryHierarchy(folder.getRoot().toString(), OverwriteFileSetting.overwriteExistingFiles,
				CompressionType.none);
	}

	/** An enabled module with minimumStayMinutes as the preparation stage writes it, or without it when null. */
	private static ParkingConfigGroup parking(String minimumStayMinutes) {
		return parking(minimumStayMinutes, null);
	}

	/** An enabled module with minimumStayMinutes and searchTimeFactor as written into the config, or without when null. */
	private static ParkingConfigGroup parking(String minimumStayMinutes, String searchTimeFactor) {
		ParkingConfigGroup parking = new ParkingConfigGroup();
		parking.setEnabled("true");
		parking.setTariffsPath("parking_tariffs_2026-09-28.json");
		if (minimumStayMinutes != null) {
			parking.addParam("minimumStayMinutes", minimumStayMinutes);
		}
		if (searchTimeFactor != null) {
			parking.addParam("searchTimeFactor", searchTimeFactor);
		}
		return parking;
	}

	private Path reportOfIteration(int iteration) {
		return folder.getRoot().toPath().resolve("ITERS").resolve("it." + iteration)
				.resolve(iteration + ".parking_outcomes.csv");
	}

	/**
	 * CSV v2: one row per non-zero cell (outcome, zone, purpose) in outcome declaration order, then zone id, then purpose;
	 * the zone id is empty for NO_ZONE; the share is the count over all counted stays of the iteration (8 here). The
	 * second log line relates the extended stays to the priced stays, the stays in a parking zone: every outcome but
	 * NO_ZONE (here 3 of 8).
	 */
	@Test
	public void writesOneRowPerNonZeroCellAndLogsTheOutcomeAndMinimumStayShares() throws Exception {
		ParkingOutcomeCounter counter = new ParkingOutcomeCounter();
		counter.record(ParkingOutcome.PAID_METERED, "fx_bs_ib", "work");
		counter.record(ParkingOutcome.NO_ZONE, null, "work");
		counter.record(ParkingOutcome.NO_ZONE, null, "shop");
		counter.record(ParkingOutcome.NO_ZONE, null, "work");
		counter.record(ParkingOutcome.PAID_METERED, "fx_bs_ia", "shop");
		counter.recordMinimumStayExtension();
		counter.record(ParkingOutcome.NO_ZONE, null, "work");
		counter.record(ParkingOutcome.EMPLOYER_FREE, "fx_bs_ib", "work");
		counter.record(ParkingOutcome.NO_ZONE, null, "shop");
		OutputDirectoryHierarchy output = output();
		ParkingOutcomeReportListener listener = new ParkingOutcomeReportListener(counter, output,
				parking("15"), tariffsWithSearchTimes);

		List<String> messages = logDuring(() -> listener.notifyIterationEnds(new IterationEndsEvent(null, 3, false)));

		Path report = reportOfIteration(3);
		assertEquals(Path.of(output.getIterationFilename(3, ParkingOutcomeReportListener.FILE_NAME)), report);
		assertEquals(List.of(V3_HEADER, //
				"NO_ZONE,,shop,2,0.250000,0.000000,0", //
				"NO_ZONE,,work,3,0.375000,0.000000,0", //
				"EMPLOYER_FREE,fx_bs_ib,work,1,0.125000,0.000000,0", //
				"PAID_METERED,fx_bs_ia,shop,1,0.125000,0.000000,0", //
				"PAID_METERED,fx_bs_ib,work,1,0.125000,0.000000,0"), Files.readAllLines(report, StandardCharsets.UTF_8));
		assertEquals(List.of("[parking] it 3: NO_ZONE 5 (62.5 %), EMPLOYER_FREE 1 (12.5 %), PAID_METERED 2 (25.0 %)",
				"[parking] it 3: minimum stay 15 min extended 1 of 3 priced stays (33.3 %)"), messages);
		// The next iteration counts from zero.
		assertEquals(0L, counter.snapshotAndReset().size());
		assertEquals(0L, counter.snapshotAndResetMinimumStayExtensions());
	}

	/** The schema-2 products have their rows too, in declaration order after the version 1 outcomes. */
	@Test
	public void listsTheGarageAndCommuterOutcomesAfterTheVersionOneOutcomes() throws Exception {
		ParkingOutcomeCounter counter = new ParkingOutcomeCounter();
		counter.record(ParkingOutcome.PAID_COMMUTER, "fx_bs_ib_v2", "work");
		counter.record(ParkingOutcome.PAID_GARAGE, "fx_wob_v2", "shop");
		counter.record(ParkingOutcome.PAID_GARAGE, "fx_wob_v2", "shop");
		counter.record(ParkingOutcome.PAID_CAMPUS_GUEST, "fx_campus_v2", "shop");
		ParkingOutcomeReportListener listener = new ParkingOutcomeReportListener(counter, output(),
				parking("15"), tariffsWithSearchTimes);

		logDuring(() -> listener.notifyIterationEnds(new IterationEndsEvent(null, 1, false)));

		assertEquals(List.of(V3_HEADER, //
				"PAID_CAMPUS_GUEST,fx_campus_v2,shop,1,0.250000,0.000000,0", //
				"PAID_GARAGE,fx_wob_v2,shop,2,0.500000,0.000000,0", //
				"PAID_COMMUTER,fx_bs_ib_v2,work,1,0.250000,0.000000,0"),
				Files.readAllLines(reportOfIteration(1), StandardCharsets.UTF_8));
	}

	/** Without replanning (iteration 0) no car trip is priced: the file holds the header only and the log lines say so. */
	@Test
	public void anIterationWithoutPricedStaysWritesTheHeaderOnly() throws Exception {
		ParkingOutcomeReportListener listener = new ParkingOutcomeReportListener(new ParkingOutcomeCounter(), output(),
				parking("15"), tariffsWithSearchTimes);

		List<String> messages = logDuring(() -> listener.notifyIterationEnds(new IterationEndsEvent(null, 0, false)));

		assertEquals(List.of(V3_HEADER),
				Files.readAllLines(reportOfIteration(0), StandardCharsets.UTF_8));
		assertEquals(List.of("[parking] it 0: no car stay priced",
				"[parking] it 0: minimum stay 15 min extended 0 of 0 priced stays (0.0 %)"), messages);
	}

	/**
	 * A zone id or an activity type with a comma, a quote or a line break is written in quotes (RFC 4180), so that a CSV
	 * reader finds the five columns again.
	 */
	@Test
	public void quotesAFieldThatContainsACommaAQuoteOrALineBreak() throws Exception {
		assertEquals("work", ParkingOutcomeReportListener.csvField("work"));
		assertEquals("", ParkingOutcomeReportListener.csvField(""));
		assertEquals("\"a,b\"", ParkingOutcomeReportListener.csvField("a,b"));
		assertEquals("\"say \"\"hi\"\"\"", ParkingOutcomeReportListener.csvField("say \"hi\""));
		assertEquals("\"two\nlines\"", ParkingOutcomeReportListener.csvField("two\nlines"));

		ParkingOutcomeCounter counter = new ParkingOutcomeCounter();
		counter.record(ParkingOutcome.PAID_METERED, "fx,zone", "work");
		ParkingOutcomeReportListener listener = new ParkingOutcomeReportListener(counter, output(),
				parking("15"), tariffsWithSearchTimes);
		logDuring(() -> listener.notifyIterationEnds(new IterationEndsEvent(null, 2, false)));
		assertEquals(List.of(V3_HEADER, "PAID_METERED,\"fx,zone\",work,1,1.000000,0.000000,0"),
				Files.readAllLines(reportOfIteration(2), StandardCharsets.UTF_8));
	}

	/**
	 * The active minimum parked duration and the state of the search time (lever 4) are logged once, when the controller
	 * starts; a config prepared before assumption L1 and lever 4 has neither and prices as version 1. The search time
	 * line never reads as active when the factor is 0 (off) or when it cannot act (no zone carries a search time).
	 */
	@Test
	public void logsTheActiveMinimumStayAndTheSearchTimeStateOnceAtStartup() {
		ParkingOutcomeReportListener listener = new ParkingOutcomeReportListener(new ParkingOutcomeCounter(), output(),
				parking("15", "1.5"), tariffsWithSearchTimes);
		assertEquals(List.of("[parking] minimum stay 15 min (braunschweigParking.minimumStayMinutes, assumption L1): every"
				+ " priced car stay lasts at least 900 s; the simulated timing is unchanged",
				"[parking] search time (lever 4): active in 3 zones (searchTimeFactor 1.5); the car utility of a trip to a"
						+ " non-home destination in such a zone adds betaTravelTime_u_min * 1.5 * search_time_min of the zone"),
				logDuring(() -> listener.notifyStartup(new StartupEvent(null))));

		ParkingOutcomeReportListener versionOne = new ParkingOutcomeReportListener(new ParkingOutcomeCounter(), output(),
				parking(null), tariffsWithSearchTimes);
		assertEquals(List.of("[parking] minimum stay 0 min (braunschweigParking.minimumStayMinutes, assumption L1): car"
				+ " stays are priced as simulated (version 1)",
				"[parking] search time (lever 4): off (searchTimeFactor 0.0); the car utility carries no parking search time"
						+ " term (version 1)"),
				logDuring(() -> versionOne.notifyStartup(new StartupEvent(null))));

		// An explicit 0 is the same as the default, also for a model without search times.
		for (ParkingTariffs tariffs : new ParkingTariffs[] { tariffsWithSearchTimes, tariffsWithoutSearchTimes }) {
			ParkingOutcomeReportListener explicitZero = new ParkingOutcomeReportListener(new ParkingOutcomeCounter(),
					output(), parking("15", "0"), tariffs);
			assertEquals("[parking] search time (lever 4): off (searchTimeFactor 0.0); the car utility carries no parking"
					+ " search time term (version 1)", logDuring(() -> explicitZero.notifyStartup(new StartupEvent(null))).get(1));
		}
	}

	/**
	 * With a factor above 0 the line says in how many zones the search time is active; it says that the term is NOT acting
	 * when no zone carries a search time (the startup check of lever 4 stops such a run, but the line must not claim an
	 * active term meanwhile), and it counts one zone as one zone.
	 */
	@Test
	public void theSearchTimeLineCountsTheZonesItActsInAndNeverClaimsAnInertTermIsActive() throws Exception {
		ParkingOutcomeReportListener withoutSearchTimes = new ParkingOutcomeReportListener(new ParkingOutcomeCounter(),
				output(), parking("15", "1.0"), tariffsWithoutSearchTimes);
		assertEquals("[parking] search time (lever 4): NOT acting, no zone of the tariff model carries a search_time_min above"
				+ " 0 (searchTimeFactor 1.0)", logDuring(() -> withoutSearchTimes.notifyStartup(new StartupEvent(null))).get(1));

		ObjectNode oneZone = (ObjectNode) new ObjectMapper().readTree(Path.of(ParkingOutcomeReportListenerTest.class
				.getResource("/parking/parking_tariffs_fixture.json").toURI()).toFile());
		((ObjectNode) oneZone.get("zones").get("fx_bs_ib_v2")).putNull("search_time_min");
		((ObjectNode) oneZone.get("zones").get("fx_wob_v2")).putNull("search_time_min");
		ParkingTariffs tariffsWithOneSearchTimeZone = ParkingTariffs.parse(oneZone);
		assertEquals(1L, tariffsWithOneSearchTimeZone.zonesWithSearchTime());
		ParkingOutcomeReportListener one = new ParkingOutcomeReportListener(new ParkingOutcomeCounter(), output(),
				parking("15", "2.5"), tariffsWithOneSearchTimeZone);
		assertEquals("[parking] search time (lever 4): active in 1 zone (searchTimeFactor 2.5); the car utility of a trip to a"
				+ " non-home destination in such a zone adds betaTravelTime_u_min * 2.5 * search_time_min of the zone",
				logDuring(() -> one.notifyStartup(new StartupEvent(null))).get(1));
	}

	/**
	 * CSV v3 with the garage options on: the garage probability of the stays of a row is summed in garage_probability_sum
	 * (here 0.25 + 0.5 over the two PAID_EXPECTED stays) and the stays with a garage in range are counted per row (the stay
	 * with a free street, E4, has probability 0 and a garage in range). The extra log line carries the rates of the
	 * counters and the expected garage share of the 4 priced stays (0.75 / 4); the NO_ZONE stay is no priced stay.
	 */
	@Test
	public void writesTheGarageProbabilityPerRowAndLogsTheGarageOptionRatesWhenTheOptionsAreOn() throws Exception {
		ParkingOutcomeCounter counter = new ParkingOutcomeCounter();
		counter.record(ParkingOutcome.PAID_EXPECTED, "fx_bs_ib", "shop", 0.25, 1);
		counter.record(ParkingOutcome.PAID_EXPECTED, "fx_bs_ib", "shop", 0.5, 2);
		counter.record(ParkingOutcome.FREE_WITHIN_LIMIT, "fx_bs_ib", "shop", 0.0, 1);
		counter.record(ParkingOutcome.PAID_METERED, "fx_bs_ib", "shop");
		counter.record(ParkingOutcome.NO_ZONE, null, "work");
		GarageOptionCounters rates = counter.garageOptionCounters();
		for (int stay = 0; stay < 4; stay++) {
			rates.countStay();
			rates.countEligible();
		}
		for (int stay = 0; stay < 3; stay++) {
			rates.countWithGaragesInRange();
			rates.countOptionEvaluation();
		}
		rates.countStreetFree();
		rates.countExpected();
		rates.countExpected();
		ParkingOutcomeReportListener listener = new ParkingOutcomeReportListener(counter, output(), parking("15"),
				tariffsWithGarageOptions);

		List<String> messages = logDuring(() -> listener.notifyIterationEnds(new IterationEndsEvent(null, 4, false)));

		assertEquals(List.of(V3_HEADER, //
				"NO_ZONE,,work,1,0.200000,0.000000,0", //
				"FREE_WITHIN_LIMIT,fx_bs_ib,shop,1,0.200000,0.000000,1", //
				"PAID_METERED,fx_bs_ib,shop,1,0.200000,0.000000,0", //
				"PAID_EXPECTED,fx_bs_ib,shop,2,0.400000,0.750000,2"),
				Files.readAllLines(reportOfIteration(4), StandardCharsets.UTF_8));
		assertEquals(List.of(
				"[parking] it 4: NO_ZONE 1 (20.0 %), FREE_WITHIN_LIMIT 1 (20.0 %), PAID_METERED 1 (20.0 %),"
						+ " PAID_EXPECTED 2 (40.0 %)",
				"[parking] it 4: minimum stay 15 min extended 0 of 4 priced stays (0.0 %)",
				"[parking] it 4: garage options: stays 4; eligible 4; garages in range 3/4 (75.0 %); street free (E4) 1/3 (33.3 %);"
						+ " street unavailable 0/3 (0.0 %); priced as expectation 2; garage options priced 3, closed schedule"
						+ " repeated (P9) 0/3 (0.0 %); expected garage share 0.1875 of 4 priced stays (sum of garage"
						+ " probabilities 0.750); garage_decay_m 400.0 m (every purpose but work and education),"
						+ " garage_decay_commute_m 250.0 m (work and education)"),
				messages);
		// The rates restart from zero with the next iteration.
		assertEquals(0L, counter.garageOptionCounters().snapshot().stays());
	}

	/**
	 * Fallback transparency: an iteration in which stays reached the garage options but none had a garage within the maximum
	 * distance is a warning (a wrong CRS or garage file prices every stay as before), not a quiet success.
	 */
	@Test
	public void warnsWhenNoEligibleStayHadAGarageInRange() throws Exception {
		ParkingOutcomeCounter counter = new ParkingOutcomeCounter();
		counter.record(ParkingOutcome.PAID_METERED, "fx_bs_ib", "shop");
		counter.record(ParkingOutcome.PAID_METERED, "fx_bs_ib", "work");
		for (int stay = 0; stay < 2; stay++) {
			counter.garageOptionCounters().countStay();
			counter.garageOptionCounters().countEligible();
		}
		ParkingOutcomeReportListener listener = new ParkingOutcomeReportListener(counter, output(), parking("15"),
				tariffsWithGarageOptions);

		List<String> messages = logDuring(() -> listener.notifyIterationEnds(new IterationEndsEvent(null, 5, false)));

		assertEquals(4, messages.size());
		assertTrue(messages.get(2), messages.get(2)
				.startsWith("[parking] it 5: garage options: stays 2; eligible 2; garages in range 0/2"));
		assertTrue(messages.get(3), messages.get(3).contains("none of 2 eligible stays had a garage within the maximum distance"));
		assertTrue(messages.get(3), messages.get(3).contains("EPSG:25832"));
	}

	/** An iteration without a stay that reached the garage options is no warning: nothing was eligible. */
	@Test
	public void doesNotWarnWhenNothingWasEligibleForTheGarageOptions() throws Exception {
		ParkingOutcomeReportListener listener = new ParkingOutcomeReportListener(new ParkingOutcomeCounter(), output(),
				parking("15"), tariffsWithGarageOptions);

		List<String> messages = logDuring(() -> listener.notifyIterationEnds(new IterationEndsEvent(null, 0, false)));

		assertEquals(3, messages.size());
		assertTrue(messages.get(2), messages.get(2).startsWith("[parking] it 0: garage options: stays 0; eligible 0;"));
		assertEquals(List.of(V3_HEADER), Files.readAllLines(reportOfIteration(0), StandardCharsets.UTF_8));
	}

	/** Messages the listener logs while {@code action} runs. */
	private static List<String> logDuring(Runnable action) {
		Logger logger = (Logger) LogManager.getLogger(ParkingOutcomeReportListener.class);
		CollectingAppender appender = new CollectingAppender();
		appender.start();
		logger.addAppender(appender);
		try {
			action.run();
		} finally {
			logger.removeAppender(appender);
			appender.stop();
		}
		return appender.messages;
	}

	private static class CollectingAppender extends AbstractAppender {
		private final List<String> messages = new ArrayList<>();

		CollectingAppender() {
			super("parking-outcome-report-test", null, PatternLayout.createDefaultLayout(), false, null);
		}

		@Override
		public void append(LogEvent event) {
			messages.add(event.getMessage().getFormattedMessage());
		}
	}
}
