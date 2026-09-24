package org.eqasim.braunschweig.fares.zonal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

public class FareOutcomeCounterTest {
	@Test
	public void countsSharesAndResets() {
		FareOutcomeCounter counter = new FareOutcomeCounter();
		counter.record(FareQuote.VRB_SINGLE_ADULT);
		counter.record(FareQuote.VRB_SINGLE_ADULT);
		counter.record(FareQuote.LONG_DISTANCE_FALLBACK);
		counter.record(FareQuote.LINE_SCOPE_MISSING);
		Map<String, Long> snapshot = counter.snapshotAndReset();
		assertEquals(Long.valueOf(2), snapshot.get(FareQuote.VRB_SINGLE_ADULT));
		assertEquals(0.5, FareOutcomeCounter.fallbackShare(snapshot), 1e-12);
		assertTrue(counter.snapshotAndReset().isEmpty());
		assertEquals(0.0, FareOutcomeCounter.fallbackShare(Map.of()), 0.0);
	}

	@Test
	public void dayTicketCapLabelIsReportedButNotPartOfTheQuoteTotal() {
		Map<String, Long> snapshot = Map.of(FareQuote.VRB_SINGLE_ADULT, 1L, FareQuote.LONG_DISTANCE_FALLBACK, 1L,
				FareQuote.DAY_TICKET_CAP_APPLIED, 8L);
		assertEquals(0.5, FareOutcomeCounter.fallbackShare(snapshot), 1e-12);
	}
}
