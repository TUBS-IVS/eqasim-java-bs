package org.eqasim.braunschweig.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

public class ParkingConfigGroupTest {
	@Test
	public void readsEveryParameterAsTheXmlReaderPassesIt() {
		// The MATSim config reader hands every parameter to the group as a string via addParam.
		ParkingConfigGroup parking = new ParkingConfigGroup();
		parking.addParam("enabled", "true");
		parking.addParam("tariffsPath", "parking_tariffs_2026-09-28.json");
		parking.addParam("terminalStayRule", "until_fee_end");
		Config config = ConfigUtils.createConfig();
		config.addModule(parking);
		assertSame(parking, ParkingConfigGroup.active(config));
		parking.requireSupported();
		assertEquals("braunschweigParking", parking.getName());
		assertTrue(parking.isEnabled());
		assertEquals("parking_tariffs_2026-09-28.json", parking.getTariffsPath());
		assertEquals("until_fee_end", parking.getTerminalStayRule());
	}

	@Test
	public void absentOrDisabledModuleIsInactive() {
		Config config = ConfigUtils.createConfig();
		assertNull(ParkingConfigGroup.active(config));
		ParkingConfigGroup parking = new ParkingConfigGroup();
		config.addModule(parking);
		assertFalse(parking.isEnabled());
		assertNull(ParkingConfigGroup.active(config));
		parking.setEnabled("false");
		assertNull(ParkingConfigGroup.active(config));
	}

	@Test
	public void enabledMustBeLiteralTrueOrFalse() {
		ParkingConfigGroup parking = new ParkingConfigGroup();
		for (String value : new String[] { "yes", "TRUE", "True", "1", "", " true" }) {
			assertThrows(value, IllegalArgumentException.class, () -> parking.setEnabled(value));
			assertThrows(value, IllegalArgumentException.class, () -> parking.addParam("enabled", value));
		}
		assertThrows(IllegalArgumentException.class, () -> parking.setEnabled(null));
		assertFalse(parking.isEnabled());
		parking.setEnabled("true");
		assertTrue(parking.isEnabled());
	}

	@Test
	public void enabledModuleRequiresATariffsPath() {
		ParkingConfigGroup parking = new ParkingConfigGroup();
		// Disabled: nothing is required.
		parking.requireSupported();
		parking.setEnabled("true");
		IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, parking::requireSupported);
		assertTrue(missing.getMessage(), missing.getMessage().contains("braunschweigParking.tariffsPath"));
		parking.setTariffsPath("  ");
		assertThrows(IllegalArgumentException.class, parking::requireSupported);
		parking.setTariffsPath("parking_tariffs_2026-09-28.json");
		parking.requireSupported();
	}

	@Test
	public void onlyTheUntilFeeEndTerminalStayRuleIsAccepted() {
		ParkingConfigGroup parking = new ParkingConfigGroup();
		assertEquals(ParkingCostCalculator.TERMINAL_STAY_RULE_UNTIL_FEE_END, parking.getTerminalStayRule());
		for (String value : new String[] { "eight_hours", "UNTIL_FEE_END", "until_fee_end ", "" }) {
			IllegalArgumentException error = assertThrows(value, IllegalArgumentException.class,
					() -> parking.setTerminalStayRule(value));
			assertTrue(error.getMessage(), error.getMessage().contains("braunschweigParking.terminalStayRule"));
		}
		assertThrows(IllegalArgumentException.class, () -> parking.addParam("terminalStayRule", "eight_hours"));
		assertThrows(IllegalArgumentException.class, () -> parking.setTerminalStayRule(null));
		assertEquals(ParkingCostCalculator.TERMINAL_STAY_RULE_UNTIL_FEE_END, parking.getTerminalStayRule());
	}

	@Test
	public void everyParameterIsDocumentedAndPricesAreNotParameters() {
		Map<String, String> comments = new ParkingConfigGroup().getComments();
		for (String parameter : new String[] { "enabled", "tariffsPath", "terminalStayRule" }) {
			assertTrue(parameter, comments.containsKey(parameter) && !comments.get(parameter).isBlank());
		}
		// Prices, fee windows and limits live only in the tariff model JSON; a config override must fail, not be ignored.
		assertThrows(IllegalArgumentException.class, () -> new ParkingConfigGroup().addParam("hourlyRateCents", "180"));
	}
}
