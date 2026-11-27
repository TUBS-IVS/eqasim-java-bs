package org.eqasim.braunschweig.analysis;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.groups.GlobalConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.scoring.ExperiencedPlansService;
import org.matsim.core.utils.io.IOUtils;

import com.google.inject.Inject;

/**
 * Writes MATSim's distance statistics with the survey diary length of the zero-length loop trips next to MATSim's own
 * files, after every iteration (eqasim-bs#679, ADR-0207 decision 7; the computation and its definitions:
 * {@link LoopTripDistances}).
 *
 * <ul>
 * <li>{@value #TRAVEL_DISTANCE_FILE}: the columns of MATSim's {@code traveldistancestats.csv} (iteration, average leg
 * distance, average trip distance, metres).</li>
 * <li>{@value #PKM_FILE}: the columns of MATSim's {@code pkm_modestats.csv} (iteration, person kilometres by leg mode,
 * rounded to whole km like MATSim's file).</li>
 * </ul>
 *
 * MATSim's own files stay as MATSim writes them. Without any trip carrying the attribute both files hold MATSim's
 * values (up to the floating-point rounding of the summation order). Every iteration logs how many trips were corrected and the kilometres before and after; executed trips that
 * cannot be matched to the selected plan are counted and make the line a warning.
 */
public class LoopTripDistanceStatsListener implements IterationEndsListener {
	public static final String TRAVEL_DISTANCE_FILE = "traveldistancestats_loops_counted.csv";
	public static final String PKM_FILE = "pkm_modestats_loops_counted.csv";

	private static final Logger logger = LogManager.getLogger(LoopTripDistanceStatsListener.class);

	private final ExperiencedPlansService experiencedPlansService;
	private final Scenario scenario;
	private final OutputDirectoryHierarchy outputDirectoryHierarchy;
	private final String delimiter;

	private final Map<Integer, double[]> averagesByIteration = new TreeMap<>();
	private final Map<Integer, Map<String, Double>> metersByIteration = new TreeMap<>();

	@Inject
	public LoopTripDistanceStatsListener(ExperiencedPlansService experiencedPlansService, Scenario scenario,
			OutputDirectoryHierarchy outputDirectoryHierarchy, GlobalConfigGroup globalConfig) {
		this.experiencedPlansService = experiencedPlansService;
		this.scenario = scenario;
		this.outputDirectoryHierarchy = outputDirectoryHierarchy;
		this.delimiter = globalConfig.getDefaultDelimiter();
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		LoopTripDistances.Result result = LoopTripDistances.compute(experiencedPlansService.getExperiencedPlans(),
				personId -> {
					Person person = scenario.getPopulation().getPersons().get(personId);
					return person == null ? null : person.getSelectedPlan();
				});
		int iteration = event.getIteration();
		averagesByIteration.put(iteration, new double[] { result.legStatistics.getAverage(),
				result.tripStatistics.getAverage() });
		metersByIteration.put(iteration, result.metersByMode);
		writeTravelDistances();
		writePersonKilometres();

		boolean unmatched = result.mismatchedTrips > 0 || result.personsWithoutSelectedPlan > 0;
		// Locale.ROOT: a decimal point in the log on every machine.
		String message = String.format(Locale.ROOT,
				"[loop trip distances] iteration %d: %d trips counted with their survey diary length (%.1f km instead of "
						+ "%.1f km route length; %d without a leg of their routing mode); %d executed trips not matched "
						+ "to the selected plan, %d persons without a selected plan; average trip distance %.1f m",
				iteration, result.correctedTrips, result.correctedTripDiaryMeters / 1000.0,
				result.correctedTripRouteMeters / 1000.0, result.correctedTripsWithoutRoutingModeLeg,
				result.mismatchedTrips, result.personsWithoutSelectedPlan, result.tripStatistics.getAverage());
		if (unmatched) {
			logger.warn(message + " -- unmatched trips keep their route length");
		} else {
			logger.info(message);
		}
	}

	private void writeTravelDistances() {
		try (BufferedWriter writer = IOUtils
				.getBufferedWriter(outputDirectoryHierarchy.getOutputFilename(TRAVEL_DISTANCE_FILE))) {
			writer.write("ITERATION" + delimiter + "avg. Average Leg distance" + delimiter + "avg. Average Trip distance\n");
			for (Map.Entry<Integer, double[]> entry : averagesByIteration.entrySet()) {
				writer.write(entry.getKey() + delimiter + entry.getValue()[0] + delimiter + entry.getValue()[1] + "\n");
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void writePersonKilometres() {
		TreeSet<String> modes = new TreeSet<>();
		metersByIteration.values().forEach(byMode -> modes.addAll(byMode.keySet()));
		try (BufferedWriter writer = IOUtils.getBufferedWriter(outputDirectoryHierarchy.getOutputFilename(PKM_FILE))) {
			StringBuilder header = new StringBuilder("Iteration");
			modes.forEach(mode -> header.append(delimiter).append(mode));
			writer.write(header + "\n");
			for (Map.Entry<Integer, Map<String, Double>> entry : metersByIteration.entrySet()) {
				StringBuilder row = new StringBuilder(String.valueOf(entry.getKey()));
				for (String mode : modes) {
					row.append(delimiter).append(Math.round(entry.getValue().getOrDefault(mode, 0.0) / 1000.0));
				}
				writer.write(row + "\n");
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
