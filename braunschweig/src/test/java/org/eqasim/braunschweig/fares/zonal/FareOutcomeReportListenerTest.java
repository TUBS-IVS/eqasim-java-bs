package org.eqasim.braunschweig.fares.zonal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class FareOutcomeReportListenerTest {
	@Test
	public void writesCsvLogsSharesAndFailsAboveTheThreshold() throws Exception {
		Path directory = Files.createTempDirectory("fare-report");
		FareOutcomeCounter counter = new FareOutcomeCounter();
		counter.record(FareQuote.VRB_SINGLE_ADULT);
		counter.record(FareQuote.VRB_PAIR_UNDEFINED_FALLBACK);
		Path csv = FareOutcomeReportListener.writeReport(counter, directory.resolve("3.vrb_fare_outcomes.csv"), 3, 0.6);
		String text = Files.readString(csv);
		assertTrue(text.startsWith("iteration,outcome,count,share\n"));
		assertTrue(text.contains("3,vrb_pair_undefined_fallback,1,0.5"));
		counter.record(FareQuote.VRB_PAIR_UNDEFINED_FALLBACK);
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> FareOutcomeReportListener.writeReport(counter, directory.resolve("4.vrb_fare_outcomes.csv"), 4, 0.05));
		assertTrue(failure.getMessage(), failure.getMessage().contains("fallback share 1.0000 exceeds 0.0500"));
		assertTrue(Files.exists(directory.resolve("4.vrb_fare_outcomes.csv")));
		assertEquals(0, counter.snapshotAndReset().size());
	}
}
