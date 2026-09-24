package org.eqasim.braunschweig.fares.zonal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;

public class VrbFareConfigGroupTest {
	@Test
	public void readsEveryParameterAsTheXmlReaderPassesIt() {
		// EqasimConfigurator.updateConfig turns the generic XML module into this typed group via addParam.
		VrbFareConfigGroup fare = new VrbFareConfigGroup();
		fare.addParam("enabled", "true");
		fare.addParam("fareModelPath", "vrb_fare_model_2026.json");
		fare.addParam("lineScopesPath", "vrb_line_scopes.csv");
		fare.addParam("dayTicketCapEnabled", "false");
		fare.addParam("maximumUnsupportedShare", "0.05");
		Config config = ConfigUtils.createConfig();
		config.addModule(fare);
		assertSame(fare, VrbFareConfigGroup.active(config));
		fare.requireSupported();
		assertEquals("vrb_fare_model_2026.json", fare.getFareModelPath());
		assertFalse(fare.isDayTicketCapEnabled());
		assertEquals(0.05, fare.maximumUnsupportedShare(), 0.0);
	}

	@Test
	public void fallbackPriceIsNotAConfigParameter() {
		// The fallback price lives only in the fare model JSON; a config override must fail, not be ignored.
		assertThrows(IllegalArgumentException.class,
				() -> new VrbFareConfigGroup().addParam("unsupportedFallbackPriceCents", "0"));
	}

	@Test
	public void absentOrDisabledModuleIsInactive() {
		Config config = ConfigUtils.createConfig();
		assertNull(VrbFareConfigGroup.active(config));
		config.addModule(new VrbFareConfigGroup());
		assertNull(VrbFareConfigGroup.active(config));
	}

	@Test
	public void enabledWithoutPathsOrWithBadNumbersIsRejected() {
		VrbFareConfigGroup fare = new VrbFareConfigGroup();
		fare.setEnabled("true");
		assertThrows(IllegalArgumentException.class, fare::requireSupported);
		fare.setFareModelPath("m.json");
		fare.setLineScopesPath("s.csv");
		assertThrows(IllegalArgumentException.class, () -> fare.setMaximumUnsupportedShare("1.5"));
		assertThrows(IllegalArgumentException.class, () -> fare.setEnabled("yes"));
		fare.requireSupported();
	}
}
