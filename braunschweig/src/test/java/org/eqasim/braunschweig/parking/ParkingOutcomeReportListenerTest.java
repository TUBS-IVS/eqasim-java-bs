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

/**
 * Per-iteration parking outcome report: {@code ITERS/it.N/N.parking_outcomes.csv} with one row per outcome and one
 * {@code [parking]} log line with the shares of the outcomes that occurred. Version 1 only reports: no threshold, no
 * failure.
 */
public class ParkingOutcomeReportListenerTest {
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private OutputDirectoryHierarchy output() {
		return new OutputDirectoryHierarchy(folder.getRoot().toString(), OverwriteFileSetting.overwriteExistingFiles,
				CompressionType.none);
	}

	@Test
	public void writesEveryOutcomeToTheIterationFileAndLogsTheSharesOfTheOutcomesThatOccurred() throws Exception {
		ParkingOutcomeCounter counter = new ParkingOutcomeCounter();
		for (int index = 0; index < 5; index++) {
			counter.record(ParkingOutcome.NO_ZONE);
		}
		counter.record(ParkingOutcome.PAID_METERED);
		counter.record(ParkingOutcome.PAID_METERED);
		counter.record(ParkingOutcome.EMPLOYER_FREE);
		OutputDirectoryHierarchy output = output();
		ParkingOutcomeReportListener listener = new ParkingOutcomeReportListener(counter, output);

		List<String> messages = logDuring(() -> listener.notifyIterationEnds(new IterationEndsEvent(null, 3, false)));

		Path report = folder.getRoot().toPath().resolve("ITERS").resolve("it.3").resolve("3.parking_outcomes.csv");
		assertEquals(Path.of(output.getIterationFilename(3, ParkingOutcomeReportListener.FILE_NAME)), report);
		assertEquals(List.of("outcome,count,share", //
				"NO_ZONE,5,0.625000", //
				"HOME,0,0.000000", //
				"EMPLOYER_FREE,1,0.125000", //
				"RESIDENT_FREE,0,0.000000", //
				"OUTSIDE_FEE_HOURS,0,0.000000", //
				"FREE_WITHIN_LIMIT,0,0.000000", //
				"PAID_METERED,2,0.250000", //
				"PAID_LONG_STAY,0,0.000000", //
				"PAID_CAMPUS_MEMBER,0,0.000000", //
				"PAID_CAMPUS_GUEST,0,0.000000"), Files.readAllLines(report, StandardCharsets.UTF_8));
		assertEquals(List.of("[parking] it 3: NO_ZONE 5 (62.5 %), EMPLOYER_FREE 1 (12.5 %), PAID_METERED 2 (25.0 %)"),
				messages);
		// The next iteration counts from zero.
		assertEquals(0L, counter.snapshotAndReset().values().stream().mapToLong(Long::longValue).sum());
	}

	/** Without replanning (iteration 0) no car trip is priced: every count and share is 0 and the log line says so. */
	@Test
	public void anIterationWithoutPricedStaysReportsZeros() throws Exception {
		ParkingOutcomeReportListener listener = new ParkingOutcomeReportListener(new ParkingOutcomeCounter(), output());

		List<String> messages = logDuring(() -> listener.notifyIterationEnds(new IterationEndsEvent(null, 0, false)));

		List<String> lines = Files.readAllLines(
				folder.getRoot().toPath().resolve("ITERS").resolve("it.0").resolve("0.parking_outcomes.csv"),
				StandardCharsets.UTF_8);
		assertEquals(1 + ParkingOutcome.values().length, lines.size());
		for (int index = 1; index < lines.size(); index++) {
			assertEquals(ParkingOutcome.values()[index - 1] + ",0,0.000000", lines.get(index));
		}
		assertEquals(List.of("[parking] it 0: no car stay priced"), messages);
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
