package org.eqasim.braunschweig.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.eqasim.braunschweig.mode_choice.parameters.BraunschweigModeParameters;
import org.eqasim.braunschweig.parking.ParkingLogCapture.Event;
import org.eqasim.core.simulation.mode_choice.parameters.ModeParameters;
import org.junit.BeforeClass;
import org.junit.Test;
import org.matsim.core.controler.events.StartupEvent;

/**
 * Startup check of lever 4 (parking search time) against the tariff model and the mode parameters: a configured factor
 * must never be silently inert, and the zone column must stay the only source of parking search time (fallback
 * transparency). The schema-2 fixture tariff model carries a search time in three zones (fx_bs_ia_v2 and fx_bs_ib_v2 5
 * min, fx_wob_v2 2 min); the schema-1 fixture has the column in none.
 */
public class ParkingSearchTimeCheckTest {
	private static final String TARIFFS_PATH = "parking_tariffs_fixture.json";

	private static ParkingTariffs withSearchTimes;
	private static ParkingTariffs withoutSearchTimes;

	@BeforeClass
	public static void readTariffs() throws Exception {
		withSearchTimes = read("/parking/parking_tariffs_fixture.json");
		withoutSearchTimes = read(ParkingCostCalculatorTest.SCHEMA_1_TARIFFS_RESOURCE);
	}

	private static ParkingTariffs read(String resourceName) throws Exception {
		URL resource = ParkingSearchTimeCheckTest.class.getResource(resourceName);
		assertNotNull("test resource " + resourceName + " is missing", resource);
		return ParkingTariffs.read(Path.of(resource.toURI()));
	}

	/** The mode parameters of a run with the given global car parking search penalty (0 is the default of every run). */
	private static ModeParameters modeParameters(double constantParkingSearchPenalty_min) {
		BraunschweigModeParameters parameters = new BraunschweigModeParameters();
		parameters.car.constantParkingSearchPenalty_min = constantParkingSearchPenalty_min;
		return parameters;
	}

	/** The check of an enabled module with the given searchTimeFactor (or without that parameter when null), penalty 0. */
	private static ParkingSearchTimeCheck check(ParkingTariffs tariffs, String searchTimeFactor) {
		return check(tariffs, searchTimeFactor, 0.0);
	}

	private static ParkingSearchTimeCheck check(ParkingTariffs tariffs, String searchTimeFactor,
			double constantParkingSearchPenalty_min) {
		ParkingConfigGroup parking = new ParkingConfigGroup();
		parking.setEnabled("true");
		parking.setTariffsPath(TARIFFS_PATH);
		if (searchTimeFactor != null) {
			parking.addParam("searchTimeFactor", searchTimeFactor);
		}
		return new ParkingSearchTimeCheck(tariffs, parking, modeParameters(constantParkingSearchPenalty_min));
	}

	private static List<Event> startup(ParkingSearchTimeCheck check) {
		return ParkingLogCapture.capture(ParkingSearchTimeCheck.class, () -> check.notifyStartup(new StartupEvent(null)));
	}

	/**
	 * A factor above 0 with no zone that has a search time cannot act: every car utility would equal the one without the
	 * term, so an arm that looks like a sensitivity arm would silently be the baseline. The run stops before iteration 0,
	 * naming the factor, the tariffs path and the missing column values.
	 */
	@Test
	public void aFactorAboveZeroWithoutAnyZoneSearchTimeFailsAtStartup() {
		for (String factor : new String[] { "1.0", "0.5", "10" }) {
			ParkingSearchTimeCheck check = check(withoutSearchTimes, factor);
			IllegalStateException error = assertThrows(IllegalStateException.class,
					() -> check.notifyStartup(new StartupEvent(null)));
			for (String fragment : new String[] { "braunschweigParking.searchTimeFactor", factor, TARIFFS_PATH,
					"no zone of the parking tariff model", "search_time_min above 0", "cannot act" }) {
				assertTrue("'" + fragment + "' missing in: " + error.getMessage(), error.getMessage().contains(fragment));
			}
		}
	}

	@Test
	public void aFactorAboveZeroWithAZoneSearchTimeStartsWithoutWarning() {
		List<Event> events = startup(check(withSearchTimes, "1.0"));
		assertTrue(events.toString(), ParkingLogCapture.messages(events, Level.WARN).isEmpty());
	}

	/** The default factor 0 (off) never fails and never warns about the search time, whatever the tariff model holds. */
	@Test
	public void aFactorOfZeroNeverFailsOrWarnsAboutTheSearchTime() {
		for (ParkingTariffs tariffs : new ParkingTariffs[] { withSearchTimes, withoutSearchTimes }) {
			for (String factor : new String[] { null, "0", "0.0" }) {
				List<Event> events = startup(check(tariffs, factor));
				assertTrue(events.toString(), ParkingLogCapture.messages(events, Level.WARN).isEmpty());
			}
		}
	}

	/**
	 * The global car.constantParkingSearchPenalty_min and the zone column are two sources of the same parking search time:
	 * with a factor above 0 both act on a car trip into a zone with a search time (double counting), so the run stops before
	 * iteration 0 naming both values and both ways out. Every value other than 0 counts, a negative one and an invalid one
	 * included.
	 */
	@Test
	public void aPenaltyAndAFactorAboveZeroTogetherFailAtStartupAsDoubleCounting() {
		for (double penalty_min : new double[] { 4.0, -2.0, Double.NaN }) {
			ParkingSearchTimeCheck check = check(withSearchTimes, "1.0", penalty_min);
			IllegalStateException error = assertThrows(IllegalStateException.class,
					() -> check.notifyStartup(new StartupEvent(null)));
			for (String fragment : new String[] { "car.constantParkingSearchPenalty_min is " + penalty_min,
					"braunschweigParking.searchTimeFactor is 1.0", "counted twice", "search_time_min",
					"--mode-choice-parameter:car.constantParkingSearchPenalty_min=0", "set searchTimeFactor to 0" }) {
				assertTrue("'" + fragment + "' missing in: " + error.getMessage(), error.getMessage().contains(fragment));
			}
		}
	}

	/**
	 * With the factor off the zone column is not a second source, but a global penalty still adds a parking search time to
	 * every car trip that the zone-based model does not know: the spec makes the zone column the only source, so the run
	 * warns once and starts.
	 */
	@Test
	public void aPenaltyWithAFactorOfZeroWarnsOnceAndStarts() {
		for (String factor : new String[] { null, "0", "0.0" }) {
			List<Event> events = startup(check(withSearchTimes, factor, 4.0));
			List<String> warnings = ParkingLogCapture.messages(events, Level.WARN);
			assertEquals(events.toString(), 1, warnings.size());
			for (String fragment : new String[] { "[parking]", "car.constantParkingSearchPenalty_min is 4.0",
					"braunschweigParking module is enabled", "only source", "search_time_min",
					"--mode-choice-parameter:car.constantParkingSearchPenalty_min=0" }) {
				assertTrue("'" + fragment + "' missing in: " + warnings.get(0), warnings.get(0).contains(fragment));
			}
		}
	}

	/** The default penalty 0 is silent whatever the factor, and a factor above 0 with a penalty of 0 starts. */
	@Test
	public void noPenaltyNeverWarnsAboutIt() {
		for (String factor : new String[] { null, "0", "1.0" }) {
			List<Event> events = startup(check(withSearchTimes, factor, 0.0));
			assertTrue(events.toString(), ParkingLogCapture.messages(events, Level.WARN).isEmpty());
		}
		// A negative zero is a zero.
		List<Event> events = startup(check(withSearchTimes, "1.0", -0.0));
		assertTrue(events.toString(), ParkingLogCapture.messages(events, Level.WARN).isEmpty());
	}
}
