package org.eqasim.braunschweig.parking;

import static org.junit.Assert.assertEquals;

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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.core.config.groups.ControllerConfigGroup.CompressionType;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.StartupEvent;

/**
 * Per-iteration parking outcome report: {@code ITERS/it.N/N.parking_outcomes.csv} (CSV v2 of the v2 spec amendment A5:
 * one row per non-zero cell of outcome, zone and purpose), one {@code [parking]} log line with the shares of the outcomes
 * that occurred and one with the share of the priced stays the minimum parked duration extended (assumption L1), whose
 * active value is logged once at startup. Version 1 only reports: no threshold, no failure.
 */
public class ParkingOutcomeReportListenerTest {
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

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
		ParkingOutcomeReportListener listener = new ParkingOutcomeReportListener(counter, output, parking("15"));

		List<String> messages = logDuring(() -> listener.notifyIterationEnds(new IterationEndsEvent(null, 3, false)));

		Path report = reportOfIteration(3);
		assertEquals(Path.of(output.getIterationFilename(3, ParkingOutcomeReportListener.FILE_NAME)), report);
		assertEquals(List.of("outcome,zone_id,purpose,count,share", //
				"NO_ZONE,,shop,2,0.250000", //
				"NO_ZONE,,work,3,0.375000", //
				"EMPLOYER_FREE,fx_bs_ib,work,1,0.125000", //
				"PAID_METERED,fx_bs_ia,shop,1,0.125000", //
				"PAID_METERED,fx_bs_ib,work,1,0.125000"), Files.readAllLines(report, StandardCharsets.UTF_8));
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
		ParkingOutcomeReportListener listener = new ParkingOutcomeReportListener(counter, output(), parking("15"));

		logDuring(() -> listener.notifyIterationEnds(new IterationEndsEvent(null, 1, false)));

		assertEquals(List.of("outcome,zone_id,purpose,count,share", //
				"PAID_CAMPUS_GUEST,fx_campus_v2,shop,1,0.250000", //
				"PAID_GARAGE,fx_wob_v2,shop,2,0.500000", //
				"PAID_COMMUTER,fx_bs_ib_v2,work,1,0.250000"), Files.readAllLines(reportOfIteration(1), StandardCharsets.UTF_8));
	}

	/** Without replanning (iteration 0) no car trip is priced: the file holds the header only and the log lines say so. */
	@Test
	public void anIterationWithoutPricedStaysWritesTheHeaderOnly() throws Exception {
		ParkingOutcomeReportListener listener = new ParkingOutcomeReportListener(new ParkingOutcomeCounter(), output(),
				parking("15"));

		List<String> messages = logDuring(() -> listener.notifyIterationEnds(new IterationEndsEvent(null, 0, false)));

		assertEquals(List.of("outcome,zone_id,purpose,count,share"),
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
		ParkingOutcomeReportListener listener = new ParkingOutcomeReportListener(counter, output(), parking("15"));
		logDuring(() -> listener.notifyIterationEnds(new IterationEndsEvent(null, 2, false)));
		assertEquals(List.of("outcome,zone_id,purpose,count,share", "PAID_METERED,\"fx,zone\",work,1,1.000000"),
				Files.readAllLines(reportOfIteration(2), StandardCharsets.UTF_8));
	}

	/**
	 * The active minimum parked duration and the active search time factor are logged once, when the controller starts; a
	 * config prepared before assumption L1 and lever 4 has neither and prices as version 1.
	 */
	@Test
	public void logsTheActiveMinimumStayAndSearchTimeFactorOnceAtStartup() {
		ParkingOutcomeReportListener listener = new ParkingOutcomeReportListener(new ParkingOutcomeCounter(), output(),
				parking("15", "1.5"));
		assertEquals(List.of("[parking] minimum stay 15 min (braunschweigParking.minimumStayMinutes, assumption L1): every"
				+ " priced car stay lasts at least 900 s; the simulated timing is unchanged",
				"[parking] search time factor 1.5 (braunschweigParking.searchTimeFactor, lever 4): the car utility of a trip"
						+ " to a destination in a zone adds betaTravelTime_u_min * 1.5 * search_time_min of that zone"),
				logDuring(() -> listener.notifyStartup(new StartupEvent(null))));

		ParkingOutcomeReportListener versionOne = new ParkingOutcomeReportListener(new ParkingOutcomeCounter(), output(),
				parking(null));
		assertEquals(List.of("[parking] minimum stay 0 min (braunschweigParking.minimumStayMinutes, assumption L1): car"
				+ " stays are priced as simulated (version 1)",
				"[parking] search time factor 0.0 (braunschweigParking.searchTimeFactor, lever 4): the car utility carries no"
						+ " parking search time term (version 1)"),
				logDuring(() -> versionOne.notifyStartup(new StartupEvent(null))));

		// An explicit 0 is the same as the default.
		ParkingOutcomeReportListener explicitZero = new ParkingOutcomeReportListener(new ParkingOutcomeCounter(), output(),
				parking("15", "0"));
		assertEquals("[parking] search time factor 0.0 (braunschweigParking.searchTimeFactor, lever 4): the car utility"
				+ " carries no parking search time term (version 1)",
				logDuring(() -> explicitZero.notifyStartup(new StartupEvent(null))).get(1));
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
