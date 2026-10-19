package org.eqasim.braunschweig.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.eqasim.braunschweig.mode_choice.parameters.BraunschweigModeParameters;
import org.eqasim.braunschweig.parking.ParkingLogCapture.Event;
import org.junit.Test;
import org.matsim.core.controler.events.StartupEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Startup check of the option price coefficient of the tariff model against the cost coefficient of the mode choice (spec v3
 * A2, ASSUMPTION G1-d; eqasim-bs issue #436): equal within 1e-9 starts, anything else stops with both values named, a model of
 * schema 4 or older (no coefficient) skips the check with one info line.
 */
public class ParkingOptionPriceBetaCheckTest {
	private static final String FIXTURE_RESOURCE = "/parking/parking_tariffs_fixture.json";
	private static final double MODE_CHOICE_BETA = 0.310998;

	private static ObjectNode fixtureTree() throws Exception {
		URL resource = ParkingOptionPriceBetaCheckTest.class.getResource(FIXTURE_RESOURCE);
		return (ObjectNode) new ObjectMapper().readTree(Path.of(resource.toURI()).toFile());
	}

	private static ParkingOptionPriceBetaCheck check(ParkingTariffs tariffs, double betaCost_u_MU) {
		BraunschweigModeParameters parameters = BraunschweigModeParameters.buildDefault();
		parameters.betaCost_u_MU = betaCost_u_MU;
		ParkingConfigGroup parking = new ParkingConfigGroup();
		parking.setEnabled("true");
		parking.setTariffsPath("parking_tariffs_fixture.json");
		return new ParkingOptionPriceBetaCheck(tariffs, parking, parameters);
	}

	private static ParkingTariffs withBeta(double optionPriceBetaPerEuro) throws Exception {
		ObjectNode tree = fixtureTree();
		tree.put("option_price_beta_u_per_eur", optionPriceBetaPerEuro);
		return ParkingTariffs.parse(tree);
	}

	private static void startup(ParkingOptionPriceBetaCheck check) {
		check.notifyStartup(new StartupEvent(null));
	}

	/** The default parameters of the mode choice carry the coefficient that the Python configuration states. */
	@Test
	public void theDefaultModeChoiceCoefficientIsTheOneOfTheFixtureModel() throws Exception {
		assertEquals(-MODE_CHOICE_BETA, BraunschweigModeParameters.buildDefault().betaCost_u_MU, 0.0);
		startup(check(withBeta(MODE_CHOICE_BETA), BraunschweigModeParameters.buildDefault().betaCost_u_MU));
	}

	/** The magnitude is compared, within 1e-9: half of the tolerance passes, twice the tolerance stops the run. */
	@Test
	public void aDifferenceWithinTheToleranceStartsAndAnythingElseStops() throws Exception {
		startup(check(withBeta(MODE_CHOICE_BETA + 5e-10), -MODE_CHOICE_BETA));
		startup(check(withBeta(MODE_CHOICE_BETA - 5e-10), -MODE_CHOICE_BETA));
		for (double beta : new double[] { MODE_CHOICE_BETA + 2e-9, MODE_CHOICE_BETA - 2e-9, 0.0, 1.0 }) {
			ParkingTariffs tariffs = withBeta(beta);
			IllegalStateException error = assertThrows("beta " + beta, IllegalStateException.class,
					() -> startup(check(tariffs, -MODE_CHOICE_BETA)));
			assertTrue(error.getMessage(), error.getMessage().contains("is " + beta));
			assertTrue(error.getMessage(), error.getMessage().contains("betaCost_u_MU is " + MODE_CHOICE_BETA));
			assertTrue(error.getMessage(), error.getMessage().contains("parking_tariffs_fixture.json"));
			assertTrue(error.getMessage(), error.getMessage().contains("ASSUMPTION G1-d"));
		}
	}

	/** The sign of betaCost_u_MU does not matter: its magnitude is the coefficient of the mode choice. */
	@Test
	public void theMagnitudeOfTheModeChoiceCoefficientIsCompared() throws Exception {
		ParkingTariffs tariffs = withBeta(0.4);
		startup(check(tariffs, -0.4));
		startup(check(tariffs, 0.4));
		assertThrows(IllegalStateException.class, () -> startup(check(tariffs, -0.5)));
		// A coefficient of the mode choice that is not a number never matches.
		assertThrows(IllegalStateException.class, () -> startup(check(tariffs, Double.NaN)));
	}

	/** A model of schema 4 or older carries no coefficient: the check is skipped and says so (never silently). */
	@Test
	public void aSchemaFourModelSkipsTheCheckWithOneInfoLine() throws Exception {
		ObjectNode tree = fixtureTree();
		tree.remove("option_price_beta_u_per_eur");
		for (var zone : tree.get("zones")) {
			((ObjectNode) zone).remove("ev_free_minutes");
		}
		for (var garage : tree.get("garages")) {
			((ObjectNode) garage).remove("max_stay_min");
		}
		tree.put("schema_version", 4);
		ParkingTariffs schemaFour = ParkingTariffs.parse(tree);
		// Whatever the mode choice value, nothing is compared.
		List<Event> events = ParkingLogCapture.capture(ParkingOptionPriceBetaCheck.class,
				() -> startup(check(schemaFour, -7.0)));
		List<String> info = ParkingLogCapture.messages(events, Level.INFO);
		assertEquals(info.toString(), 1, info.size());
		assertTrue(info.get(0), info.get(0).contains("check skipped"));
		assertTrue(info.get(0), info.get(0).contains("schema 4"));
		assertTrue(ParkingLogCapture.messages(events, Level.WARN).isEmpty());
	}

	/** A passing check logs the two values it compared. */
	@Test
	public void aPassingCheckLogsBothValues() throws Exception {
		List<Event> events = ParkingLogCapture.captureChecked(ParkingOptionPriceBetaCheck.class,
				() -> startup(check(withBeta(MODE_CHOICE_BETA), -MODE_CHOICE_BETA)));
		List<String> info = ParkingLogCapture.messages(events, Level.INFO);
		assertEquals(info.toString(), 1, info.size());
		assertTrue(info.get(0), info.get(0).contains("0.310998 per EUR equals the magnitude"));
	}
}
