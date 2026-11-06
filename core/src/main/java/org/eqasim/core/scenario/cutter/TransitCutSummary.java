package org.eqasim.core.scenario.cutter;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.core.misc.Constants;
import org.eqasim.core.scenario.cutter.population.trips.TransitTripProcessor;
import org.eqasim.core.scenario.routing.PlanRouter;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.Trip;

/**
 * The counts of the transit cut of the scenario cutter in the kept-stops mode (eqasim-bs#574, design R12): stops
 * kept by class, routes kept and dropped, pt trips with crossing points, and per collapse case the collapsed trips
 * and those of them whose routed result is a walk.
 *
 * The collapsed trips are counted in the routed population: a trip is collapsed with case c if its first leg carries
 * {@link Constants#CUT_TRANSIT_COLLAPSE_ATTRIBUTE} = c, and walked if any of its legs carries
 * {@link PlanRouter#WALK_FALLBACK_ATTRIBUTE}. Both counts thus describe the same set of trips. The eqasim-bs prepare
 * stage reads the file by its metric names.
 */
public record TransitCutSummary(long stopsKeptInside, long stopsKeptHub, long stopsKeptOtherOutside, long routesKept,
		long routesDropped, long ptTripsWithCrossings, long collapsedInsideInside, long collapsedInsideInsideWalked,
		long collapsedOutsideOrigin, long collapsedOutsideOriginWalked, long collapsedOutsideDestination,
		long collapsedOutsideDestinationWalked, long collapsedThrough, long collapsedThroughWalked) {
	private final static Logger log = LogManager.getLogger(TransitCutSummary.class);

	/**
	 * Joins the collapse markers with the walk-fallback stamps of all plans of the population. Warns per
	 * case when the population holds a different number of collapsed trips than the processor counted at the cut
	 * (e.g. when plans were removed in between).
	 */
	public static TransitCutSummary collect(Population population, Map<String, Long> collapseCounts,
			int stopsKeptInside, int stopsKeptHub, int stopsKeptOtherOutside, int routesKept, int routesDropped) {
		Map<String, long[]> perCase = new LinkedHashMap<>();

		for (String collapseCase : TransitTripProcessor.COLLAPSE_CASES) {
			perCase.put(collapseCase, new long[2]);
		}

		// Every plan, like the population cutter and the router, so that the counts match the processor's
		for (Person person : population.getPersons().values()) {
			for (Plan plan : person.getPlans()) {
				collectPlan(person, plan, perCase);
			}
		}

		for (Map.Entry<String, long[]> entry : perCase.entrySet()) {
			long counted = collapseCounts.getOrDefault(entry.getKey(), 0L);

			if (counted != entry.getValue()[0]) {
				log.warn(String.format(Locale.ROOT,
						"[TransitCutSummary] the cut collapsed %d %s trips, the routed population holds %d", counted,
						entry.getKey(), entry.getValue()[0]));
			}
		}

		return new TransitCutSummary(stopsKeptInside, stopsKeptHub, stopsKeptOtherOutside, routesKept, routesDropped,
				collapseCounts.getOrDefault(TransitTripProcessor.TRIPS_WITH_CROSSINGS, 0L),
				perCase.get(TransitTripProcessor.INSIDE_INSIDE)[0], perCase.get(TransitTripProcessor.INSIDE_INSIDE)[1],
				perCase.get(TransitTripProcessor.OUTSIDE_ORIGIN)[0], perCase.get(TransitTripProcessor.OUTSIDE_ORIGIN)[1],
				perCase.get(TransitTripProcessor.OUTSIDE_DESTINATION)[0],
				perCase.get(TransitTripProcessor.OUTSIDE_DESTINATION)[1],
				perCase.get(TransitTripProcessor.THROUGH)[0], perCase.get(TransitTripProcessor.THROUGH)[1]);
	}

	/** Adds the collapsed (index 0) and the walked (index 1) trips of one plan to the per-case counts. */
	private static void collectPlan(Person person, Plan plan, Map<String, long[]> perCase) {
		for (Trip trip : TripStructureUtils.getTrips(plan)) {
			List<Leg> legs = trip.getLegsOnly();
			Object collapseCase = legs.get(0).getAttributes().getAttribute(Constants.CUT_TRANSIT_COLLAPSE_ATTRIBUTE);

			if (collapseCase == null) {
				continue;
			}

			long[] counts = perCase.get(collapseCase.toString());

			if (counts == null) {
				throw new IllegalStateException("Unknown value '" + collapseCase + "' of the leg attribute "
						+ Constants.CUT_TRANSIT_COLLAPSE_ATTRIBUTE + " for person " + person.getId());
			}

			counts[0]++;

			if (legs.stream()
					.anyMatch(leg -> leg.getAttributes().getAttribute(PlanRouter.WALK_FALLBACK_ATTRIBUTE) != null)) {
				counts[1]++;
			}
		}
	}

	/** The metrics by name, in the order of the CSV file. */
	public Map<String, Long> metrics() {
		Map<String, Long> metrics = new LinkedHashMap<>();
		metrics.put("stops_kept_inside", stopsKeptInside);
		metrics.put("stops_kept_hub", stopsKeptHub);
		metrics.put("stops_kept_other_outside", stopsKeptOtherOutside);
		metrics.put("routes_kept", routesKept);
		metrics.put("routes_dropped", routesDropped);
		metrics.put("pt_trips_with_crossings", ptTripsWithCrossings);
		metrics.put("collapsed_inside_inside", collapsedInsideInside);
		metrics.put("collapsed_inside_inside_walked", collapsedInsideInsideWalked);
		metrics.put("collapsed_outside_origin", collapsedOutsideOrigin);
		metrics.put("collapsed_outside_origin_walked", collapsedOutsideOriginWalked);
		metrics.put("collapsed_outside_destination", collapsedOutsideDestination);
		metrics.put("collapsed_outside_destination_walked", collapsedOutsideDestinationWalked);
		metrics.put("collapsed_through", collapsedThrough);
		metrics.put("collapsed_through_walked", collapsedThroughWalked);
		return metrics;
	}

	/** Writes the metrics as a UTF-8 CSV with the header {@code metric,value} and one integer row per metric. */
	public void write(Path csv) throws IOException {
		try (Writer writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
			writer.write("metric,value\n");

			for (Map.Entry<String, Long> entry : metrics().entrySet()) {
				writer.write(entry.getKey() + "," + entry.getValue() + "\n");
			}
		}
	}

	/** A count as a rate of its denominator, e.g. {@code 1/4 (25.0 %)}; 0 % for an empty denominator. */
	public static String rate(long count, long total) {
		return String.format(Locale.ROOT, "%d/%d (%.1f %%)", count, total, total == 0 ? 0.0 : 100.0 * count / total);
	}

	/** Logs every count as a rate of its denominator. */
	public void log() {
		long stops = stopsKeptInside + stopsKeptHub + stopsKeptOtherOutside;
		long routes = routesKept + routesDropped;

		log.info("[TransitCutSummary] stops kept: inside " + rate(stopsKeptInside, stops) + ", hub "
				+ rate(stopsKeptHub, stops) + ", other outside " + rate(stopsKeptOtherOutside, stops));
		log.info("[TransitCutSummary] routes kept " + rate(routesKept, routes) + ", dropped "
				+ rate(routesDropped, routes));
		log.info("[TransitCutSummary] pt trips with crossings " + ptTripsWithCrossings);
		logCase(TransitTripProcessor.INSIDE_INSIDE, collapsedInsideInside, collapsedInsideInsideWalked);
		logCase(TransitTripProcessor.OUTSIDE_ORIGIN, collapsedOutsideOrigin, collapsedOutsideOriginWalked);
		logCase(TransitTripProcessor.OUTSIDE_DESTINATION, collapsedOutsideDestination,
				collapsedOutsideDestinationWalked);
		logCase(TransitTripProcessor.THROUGH, collapsedThrough, collapsedThroughWalked);
	}

	private void logCase(String collapseCase, long collapsed, long walked) {
		log.info("[TransitCutSummary] collapsed " + collapseCase + " " + rate(collapsed, ptTripsWithCrossings)
				+ " of the pt trips with crossings; walked " + rate(walked, collapsed));
	}
}
