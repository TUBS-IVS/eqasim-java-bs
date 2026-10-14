package org.eqasim.braunschweig.parking;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.braunschweig.parking.ParkingOutcomeCounter.Cell;
import org.eqasim.braunschweig.parking.ParkingOutcomeCounter.CellTotals;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.StartupListener;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Reports the parking outcome counts of every iteration (design "parking cost zones", section 5.2, and the outcome CSV v2
 * of the v2 spec amendment A5; eqasim-bs issue #436): writes {@code ITERS/it.N/N.parking_outcomes.csv} and logs one line
 * {@code [parking] it N: NO_ZONE a (x.x %), PAID_METERED b (y.y %), ...} with every outcome that occurred, in
 * declaration order, or {@code [parking] it N: no car stay priced} when none did, followed by the line
 * {@code [parking] it N: minimum stay L min extended e of p priced stays (z.z %)} of assumption L1 and, with the garage
 * options on (schema 3 or 4 model, {@code garage_decay_m} or {@code garage_decay_commute_m} above 0), the line
 * {@code [parking] it N: garage options: ...} with the rates of {@link GarageOptionCounters.Snapshot} (stays, eligible,
 * garages in range, street free, street unavailable, expected, option evaluations, repeated closed schedules), the expected
 * garage share of the priced stays and, at its end, both decay lengths (lambda and lambda_c, spec Amendment H5); a WARN is
 * logged when no eligible stay of the iteration had a garage in range (a wrong CRS or garage file looks like that). At the
 * controller start it logs the active minimum parked duration and the active search time factor once.
 *
 * <p>CSV v4 (spec v3 A4) appends six columns to the columns of v3 below, which keep their names and order, so that a reader of
 * v3 reads the first seven columns of a v4 file unchanged: {@code garage_probability_sum_paid} and
 * {@code garage_probability_sum_free}, the parts of {@code garage_probability_sum} that lie on garage options which cost more
 * than 0 ct and exactly 0 ct for the stay (their sum is {@code garage_probability_sum}, six decimals each: the paid-only
 * garage share of the commuters can be computed exactly); {@code street_infeasible_count}, the stays of the row whose street
 * side was no option (the maximum stay removed the street, the long-stay product and the zone commuter product, spec v3 A1);
 * {@code no_feasible_option_count}, the stays of the row priced NO_FEASIBLE_OPTION (the {@code count} of a NO_FEASIBLE_OPTION
 * row, 0 in every other row); {@code expected_cost_cents_sum}, the sum over the stays of the row of the expected parking cost in
 * euro cents (the unrounded expectation of a stay priced as an expectation, else its priced cents; six decimals) UNSCALED by
 * the cost factor, so that the mean expected cost of a zone group and a purpose is this sum over {@code count}; and
 * {@code ev_exempt_count}, the stays of the row priced EV_EXEMPT (the {@code count} of an EV_EXEMPT row, 0 in every other row).
 * The two outcome counts repeat the outcome of the row, so that a reader that sums rows by zone and purpose needs no outcome
 * filter. A run without the v3 mechanics writes 0 in the paid and free columns where no garage acted, in the infeasible and
 * the outcome counts, and the priced cents in the cost column.
 *
 * <p>CSV v3 (spec Amendment E7 of parking cost zones v2) appends two columns to the columns of v2 below, which keep their
 * names and order: {@code garage_probability_sum}, the sum over the stays of the row of the garage probability (the
 * probability that the stay uses one of the garages within the maximum distance, spec E3; 0 for a stay no garage acted on,
 * six decimals), and {@code stays_with_garages_in_range}, the stays of the row that had at least one garage within the
 * maximum distance, whether or not the garage acted (a stay with a free street pays 0 and has probability 0, spec E4). The
 * expected garage share of a zone and a purpose is the sum of {@code garage_probability_sum} over its rows divided by the
 * sum of {@code count} over the same rows (restrict the rows to the stays of the universe asked for, for example by
 * outcome); the stays priced as an expectation are the rows of the outcome PAID_EXPECTED. A run with the garage options off
 * writes 0 in both columns. Readers of v2 read the first five columns and ignore the rest; v1 is described below.
 *
 * <p>CSV v2 columns: {@code outcome} (a {@link ParkingOutcome} name), {@code zone_id} (the parking zone of the stays, empty
 * for NO_ZONE, the stays outside every zone), {@code purpose} (the activity type of the destination), {@code count} (car
 * stays the rule priced in that zone for that purpose in the iteration) and {@code share} (count divided by all counted
 * stays of the iteration, a fraction in [0, 1] with six decimals). One row per cell (outcome, zone, purpose) with a
 * non-zero count, in outcome declaration order, then zone id, then purpose, so a file lists exactly the cells that
 * occurred; an iteration without a priced stay has the header only. The rows are aggregate counts, no persons. A value
 * with a comma, a quote or a line break is written in quotes, as RFC 4180 has it. The file name is unchanged; version 1
 * of the report had the columns {@code outcome,count,share} and one row per outcome, zero counts included; readers of
 * version 1 (the smoke harness of the first zone parking run) read that format only.
 *
 * <p>Minimum parked duration (assumption L1, {@code braunschweigParking.minimumStayMinutes} = L): e counts the priced
 * stays whose priced interval the minimum extended, because the simulated stay (for the last activity of a plan the
 * stay of the terminal-stay rule T1) was shorter than L minutes; p counts the priced stays, the counted stays in a
 * parking zone: every outcome but NO_ZONE, which the car cost model decides before any stay exists. The share is e / p
 * in percent, 0 when p is 0. An extended stay need not cost more: the extension can lie outside the fee window, and the
 * home, employer-free and resident rules price 0 whatever the duration. The CSV does not carry these counts.
 *
 * <p>The counts are the calls of the zone parking car cost model in the iteration, one per car alternative that mode
 * choice priced, chosen or not: numbers of pricing calls, not of distinct trips or persons. The eqasim car predictor
 * (CarPredictor, a CachedVariablePredictor) calls the cost model whenever it is asked for another trip than on its
 * previous call: it keeps the variables of that one trip only, keyed by the identity of the DiscreteModeChoiceTrip
 * object, not by the route. Before it, the trip estimate cache of the discrete mode choice contrib (CachedTripEstimator,
 * for the cachedModes, which include car in the eqasim and Braunschweig configs) answers a repeated estimate of the same
 * trip at the same departure second without a call. Replanning happens before the iteration ends, so the iteration-0
 * file, written before any replanning, has no row. Version 1 reports only: no threshold, no failure.
 */
@Singleton
public final class ParkingOutcomeReportListener implements StartupListener, IterationEndsListener {
	private static final Logger LOG = LogManager.getLogger(ParkingOutcomeReportListener.class);

	/** File name inside the iteration directory; MATSim prefixes it with the iteration number. */
	static final String FILE_NAME = "parking_outcomes.csv";

	/** The columns of the report, CSV v4 (spec v3 A4): v3 (v2 plus the two garage option columns) plus six columns. */
	static final String HEADER = "outcome,zone_id,purpose,count,share,garage_probability_sum,stays_with_garages_in_range,"
			+ "garage_probability_sum_paid,garage_probability_sum_free,street_infeasible_count,no_feasible_option_count,"
			+ "expected_cost_cents_sum,ev_exempt_count";

	private final ParkingOutcomeCounter counter;
	private final OutputDirectoryHierarchy output;
	private final int minimumStayMinutes;
	private final int minimumStay_s;
	private final double searchTimeFactor;
	private final String searchTimeFactorText;
	private final long zonesWithSearchTime;
	private final boolean garageOptionsActive;
	private final String garageDecaySetting;

	@Inject
	public ParkingOutcomeReportListener(ParkingOutcomeCounter counter, OutputDirectoryHierarchy output,
			ParkingConfigGroup parking, ParkingTariffs tariffs) {
		this.counter = counter;
		this.output = output;
		this.minimumStayMinutes = parking.minimumStayMinutes();
		this.minimumStay_s = parking.minimumStay_s();
		this.searchTimeFactor = parking.searchTimeFactor();
		this.searchTimeFactorText = parking.getSearchTimeFactor();
		this.zonesWithSearchTime = tariffs.zonesWithSearchTime();
		this.garageOptionsActive = tariffs.garageOptions().isActive();
		this.garageDecaySetting = "garage_decay_m " + tariffs.garageDecayMeters() + " m (every purpose but work and"
				+ " education), garage_decay_commute_m " + tariffs.garageCommuteDecayMeters() + " m (work and education)";
	}

	/**
	 * Logs the active minimum parked duration (assumption L1) and the active search time factor (lever 4) once. The state of
	 * the garage options (spec Amendment E) is logged once by {@link ParkingTariffs#read}, when the controller builds this
	 * listener's tariff model.
	 */
	@Override
	public void notifyStartup(StartupEvent event) {
		LOG.info(minimumStaySetting(minimumStayMinutes, minimumStay_s));
		LOG.info(searchTimeSetting(searchTimeFactor, searchTimeFactorText, zonesWithSearchTime));
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		int iteration = event.getIteration();
		Map<Cell, CellTotals> cells = counter.snapshotAndResetTotals();
		long extendedStays = counter.snapshotAndResetMinimumStayExtensions();
		GarageOptionCounters.Snapshot garageRates = counter.garageOptionCounters().snapshotAndReset();
		Path path = Path.of(output.getIterationFilename(iteration, FILE_NAME));
		try {
			writeCsv(path, cells);
		} catch (IOException error) {
			throw new UncheckedIOException("cannot write the parking outcome report " + path, error);
		}
		Map<ParkingOutcome, Long> counts = ParkingOutcomeCounter.countsByOutcome(countsOf(cells));
		LOG.info(summary(iteration, counts));
		LOG.info(minimumStaySummary(iteration, minimumStayMinutes, extendedStays, pricedStays(counts)));
		noFeasibleOptionWarning(iteration, counts).ifPresent(LOG::warn);
		if (garageOptionsActive) {
			LOG.info(garageOptionsSummary(iteration, garageRates, cells, pricedStays(counts), garageDecaySetting));
			if (garageRates.eligible() > 0 && garageRates.withGaragesInRange() == 0) {
				// Fallback transparency: every eligible stay was priced without a garage, which is the pre-garage price. A
				// run in which no priced stay has a garage in range is far more likely a wrong CRS or a wrong garage file than
				// a region without garages.
				LOG.warn("[parking] it " + iteration + ": none of " + garageRates.eligible() + " eligible stays had a garage"
						+ " within the maximum distance although the garage options are on; check that the activity coordinates"
						+ " and the garage coordinates of the tariff model are both in EPSG:25832");
			}
		}
	}

	/**
	 * The warning of an iteration in which stays were priced NO_FEASIBLE_OPTION (spec v3 A1, ASSUMPTION M3): such a stay exceeds
	 * the maximum stay of the street and of every garage option within reach and is priced as the street at its metered price,
	 * a fallback price, so the count and the rate over the priced stays are named at WARN level; empty when none occurred.
	 */
	static Optional<String> noFeasibleOptionWarning(int iteration, Map<ParkingOutcome, Long> counts) {
		long noFeasibleOption = counts.getOrDefault(ParkingOutcome.NO_FEASIBLE_OPTION, 0L);
		if (noFeasibleOption == 0) {
			return Optional.empty();
		}
		long priced = pricedStays(counts);
		return Optional.of(String.format(Locale.ROOT,
				"[parking] it %d: %d of %d priced stays (%.1f %%) have no feasible parking option (NO_FEASIBLE_OPTION: the stay"
						+ " exceeds the maximum stay of the street and of every garage within reach) and are priced as the street"
						+ " at"
						+ " its metered price; the option set of these zones is incomplete (spec v3 A1, ASSUMPTION M3)",
				iteration, noFeasibleOption, priced, 100.0 * share(noFeasibleOption, priced)));
	}

	private static Map<Cell, Long> countsOf(Map<Cell, CellTotals> cells) {
		Map<Cell, Long> counts = new TreeMap<>();
		cells.forEach((cell, totals) -> counts.put(cell, totals.count()));
		return counts;
	}

	/**
	 * The garage option line of one iteration (spec Amendment E7): the rates of {@link GarageOptionCounters.Snapshot} and the
	 * expected garage share of all priced stays (the sum of their garage probabilities divided by their number).
	 */
	private static String garageOptionsSummary(int iteration, GarageOptionCounters.Snapshot rates, Map<Cell, CellTotals> cells,
			long pricedStays, String garageDecaySetting) {
		double garageProbabilitySum = 0.0;
		for (Map.Entry<Cell, CellTotals> entry : cells.entrySet()) {
			if (entry.getKey().outcome() != ParkingOutcome.NO_ZONE) {
				garageProbabilitySum += entry.getValue().garageProbabilitySum();
			}
		}
		return String.format(Locale.ROOT, "[parking] it %d: garage options: %s; expected garage share %.4f of %d priced stays"
				+ " (sum of garage probabilities %.3f); %s", iteration, rates.summary(), share(garageProbabilitySum, pricedStays),
				pricedStays, garageProbabilitySum, garageDecaySetting);
	}

	/** The startup line of the minimum parked duration; a minimum of 0 is the version 1 pricing. */
	private static String minimumStaySetting(int minimumStayMinutes, int minimumStay_s) {
		String setting = "[parking] minimum stay " + minimumStayMinutes + " min (" + ParkingConfigGroup.GROUP_NAME
				+ ".minimumStayMinutes, assumption L1): ";
		if (minimumStayMinutes == 0) {
			return setting + "car stays are priced as simulated (version 1)";
		}
		return setting + "every priced car stay lasts at least " + minimumStay_s + " s; the simulated timing is unchanged";
	}

	/**
	 * The startup line of the search time (lever 4): off for a factor of 0 (the car utility of version 1), active in N zones
	 * for a factor above 0 with N zones that carry a search time, and NOT acting for a factor above 0 without any such zone,
	 * so the line never reads as active when the term cannot act.
	 */
	private static String searchTimeSetting(double searchTimeFactor, String searchTimeFactorText, long zonesWithSearchTime) {
		String setting = "[parking] search time (lever 4): ";
		if (searchTimeFactor == 0.0) {
			return setting + "off (searchTimeFactor " + searchTimeFactorText + "); the car utility carries no parking search"
					+ " time term (version 1)";
		}
		if (zonesWithSearchTime == 0) {
			return setting + "NOT acting, no zone of the tariff model carries a search_time_min above 0 (searchTimeFactor "
					+ searchTimeFactorText + ")";
		}
		return setting + "active in " + zonesWithSearchTime + (zonesWithSearchTime == 1 ? " zone" : " zones")
				+ " (searchTimeFactor " + searchTimeFactorText + "); the car utility of a trip to a non-home destination in such"
				+ " a zone adds betaTravelTime_u_min * " + searchTimeFactorText + " * search_time_min of the zone";
	}

	/** The minimum parked duration line of one iteration: the priced stays it extended, as a count and in percent. */
	private static String minimumStaySummary(int iteration, int minimumStayMinutes, long extendedStays, long pricedStays) {
		return String.format(Locale.ROOT, "[parking] it %d: minimum stay %d min extended %d of %d priced stays (%.1f %%)",
				iteration, minimumStayMinutes, extendedStays, pricedStays, 100.0 * share(extendedStays, pricedStays));
	}

	/** Counted stays in a parking zone: every outcome but NO_ZONE, which the cost model decides before any stay exists. */
	private static long pricedStays(Map<ParkingOutcome, Long> counts) {
		return total(counts) - counts.getOrDefault(ParkingOutcome.NO_ZONE, 0L);
	}

	private static void writeCsv(Path path, Map<Cell, CellTotals> cells) throws IOException {
		long total = cells.values().stream().mapToLong(CellTotals::count).sum();
		StringBuilder csv = new StringBuilder(HEADER).append('\n');
		for (Map.Entry<Cell, CellTotals> entry : cells.entrySet()) {
			Cell cell = entry.getKey();
			CellTotals totals = entry.getValue();
			csv.append(cell.outcome().name()).append(',').append(csvField(cell.zoneId())).append(',')
					.append(csvField(cell.purpose())).append(',').append(totals.count()).append(',')
					.append(String.format(Locale.ROOT, "%.6f", share(totals.count(), total))).append(',')
					.append(String.format(Locale.ROOT, "%.6f", totals.garageProbabilitySum())).append(',')
					.append(totals.staysWithGaragesInRange()).append(',')
					.append(String.format(Locale.ROOT, "%.6f", totals.garageProbabilitySumPaid())).append(',')
					.append(String.format(Locale.ROOT, "%.6f", totals.garageProbabilitySumFree())).append(',')
					.append(totals.streetInfeasibleCount()).append(',').append(totals.noFeasibleOptionCount()).append(',')
					.append(String.format(Locale.ROOT, "%.6f", totals.expectedCostCentsSum())).append(',')
					.append(totals.evExemptCount()).append('\n');
		}
		if (path.getParent() != null) {
			Files.createDirectories(path.getParent());
		}
		Files.writeString(path, csv.toString(), StandardCharsets.UTF_8);
	}

	/** A text field of the CSV: quoted, with doubled quotes, when it contains a comma, a quote or a line break. */
	static String csvField(String value) {
		if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
			return value;
		}
		return '"' + value.replace("\"", "\"\"") + '"';
	}

	/** The {@code [parking]} log line of one iteration: every outcome with a non-zero count and its share in percent. */
	private static String summary(int iteration, Map<ParkingOutcome, Long> counts) {
		long total = total(counts);
		if (total == 0) {
			return "[parking] it " + iteration + ": no car stay priced";
		}
		List<String> parts = new ArrayList<>();
		for (ParkingOutcome outcome : ParkingOutcome.values()) {
			long count = counts.getOrDefault(outcome, 0L);
			if (count > 0) {
				parts.add(String.format(Locale.ROOT, "%s %d (%.1f %%)", outcome.name(), count, 100.0 * share(count, total)));
			}
		}
		return "[parking] it " + iteration + ": " + String.join(", ", parts);
	}

	private static double share(long count, long total) {
		return total == 0 ? 0.0 : count / (double) total;
	}

	private static double share(double sum, long total) {
		return total == 0 ? 0.0 : sum / total;
	}

	private static long total(Map<ParkingOutcome, Long> counts) {
		return counts.values().stream().mapToLong(Long::longValue).sum();
	}
}
