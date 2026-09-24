package org.eqasim.braunschweig.fares.zonal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;

public class VrbFareConfigGroupTest {
	@Test
	public void promotesGenericXmlModuleAndValidates() {
		Config config = ConfigUtils.createConfig();
		ConfigGroup raw = new ConfigGroup(VrbFareConfigGroup.GROUP_NAME);
		raw.addParam("enabled", "true");
		raw.addParam("fareModelPath", "vrb_fare_model_2026.json");
		raw.addParam("lineScopesPath", "vrb_line_scopes.csv");
		raw.addParam("dayTicketCapEnabled", "false");
		raw.addParam("unsupportedFallbackPriceCents", "370");
		raw.addParam("maximumUnsupportedShare", "0.05");
		config.addModule(raw);
		VrbFareConfigGroup.promoteIfPresent(config);
		VrbFareConfigGroup fare = VrbFareConfigGroup.active(config);
		assertNotNull(fare);
		fare.requireSupported();
		assertEquals("vrb_fare_model_2026.json", fare.getFareModelPath());
		assertFalse(fare.isDayTicketCapEnabled());
		assertEquals(370L, fare.unsupportedFallbackPriceCents());
		assertEquals(0.05, fare.maximumUnsupportedShare(), 0.0);
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
		assertThrows(IllegalArgumentException.class, () -> fare.setUnsupportedFallbackPriceCents("-1"));
		assertThrows(IllegalArgumentException.class, () -> fare.setEnabled("yes"));
		fare.requireSupported();
	}
}
