package org.eqasim.braunschweig.analysis;

import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Route;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.Trip;

/**
 * MATSim's per-iteration distance statistics with the survey diary length of the zero-length loop trips
 * (eqasim-bs#679, ADR-0207 decision 7).
 *
 * <p>
 * The Braunschweig synthesis places a stroll or dog walk that has no destination of its own (a "loop") at the place of
 * the activity before it. The trip into it then starts and ends at one place and has a route of (almost) 0 m, and so
 * has the trip out of it when it goes back to the loop's start place. The synthesis writes the survey diary length of
 * such a trip (km, a path length) as the attribute {@link #LOOP_TRIP_DIARY_DISTANCE_ATTRIBUTE} on the trip's
 * DESTINATION activity. MATSim's own {@code traveldistancestats.csv} and {@code pkm_modestats.csv} count these trips
 * with their route length; this class computes the same two statistics with the diary length instead, so an analysis
 * can report the walked and cycled kilometres of the strolls. The rule for analyses is documented in eqasim-bs
 * {@code docs/codebase/notes/leisure-loops.md}, section "Loop lengths for analyses".
 *
 * <p>
 * Definitions, the same as MATSim's {@code TravelDistanceStats} and {@code PKMbyModeCalculator} (read from the MATSim
 * version this project uses): over the EXECUTED plans of the iteration, a leg's distance is its route distance (NaN
 * without a route); the leg statistic is over every leg with a finite distance, the trip statistic over every trip whose
 * summed leg distance is finite; the person kilometres sum the finite leg distances by leg mode. The only difference:
 * a trip whose destination activity carries a diary length counts that length. Its main leg (the first leg whose mode
 * is the trip's routing mode, else its first leg) gets the diary length minus the finite lengths of the trip's other
 * legs (never below 0), so the trip's legs add up to the diary length and keep their modes.
 *
 * <p>
 * The executed plans are rebuilt from the events and carry no activity attributes, so the attribute is read from the
 * person's SELECTED plan (the plan executed in this iteration), trip by trip in the same order. A trip whose destination
 * activity type differs between the two plans is not corrected and counted ({@link Result#mismatchedTrips}); so is a
 * person without a selected plan. A value of the attribute that is no positive finite {@code java.lang.Double} throws,
 * because it cannot be a diary length.
 */
public final class LoopTripDistances {
	/** The activity attribute the eqasim-bs plans writer sets (column loop_trip_diary_distance_km). */
	public static final String LOOP_TRIP_DIARY_DISTANCE_ATTRIBUTE = "loopTripDiaryDistanceKm";

	private LoopTripDistances() {
	}

	/** The statistics of one iteration and the counts of the correction. */
	public static final class Result {
		public final DoubleSummaryStatistics legStatistics = new DoubleSummaryStatistics();
		public final DoubleSummaryStatistics tripStatistics = new DoubleSummaryStatistics();
		/** Sum of the finite leg distances by leg mode, metres. */
		public final Map<String, Double> metersByMode = new TreeMap<>();
		/** Trips whose distance was replaced by the diary length. */
		public long correctedTrips;
		/** Sum of the diary lengths of the corrected trips, metres. */
		public double correctedTripDiaryMeters;
		/** Sum of the route lengths those trips had before the correction (finite legs only), metres. */
		public double correctedTripRouteMeters;
		/** Corrected trips without a leg of their routing mode, whose first leg took the diary length. */
		public long correctedTripsWithoutRoutingModeLeg;
		/** Executed trips whose destination activity type differs from the selected plan's, or beyond its trips. */
		public long mismatchedTrips;
		/** Persons with an executed plan but no selected plan. */
		public long personsWithoutSelectedPlan;
	}

	/**
	 * Computes the statistics of one iteration.
	 *
	 * @param executedPlans the executed plans of the iteration (MATSim's {@code ExperiencedPlansService})
	 * @param selectedPlans the selected plan of a person, or null when there is none
	 */
	public static Result compute(Map<Id<Person>, Plan> executedPlans, Function<Id<Person>, Plan> selectedPlans) {
		Result result = new Result();
		for (Map.Entry<Id<Person>, Plan> entry : executedPlans.entrySet()) {
			Plan selected = selectedPlans.apply(entry.getKey());
			if (selected == null) {
				result.personsWithoutSelectedPlan++;
			}
			List<Trip> selectedTrips = selected == null ? List.of() : TripStructureUtils.getTrips(selected);
			List<Trip> executedTrips = TripStructureUtils.getTrips(entry.getValue());
			for (int index = 0; index < executedTrips.size(); index++) {
				Trip executed = executedTrips.get(index);
				Double diaryKm = null;
				if (selected != null) {
					Trip planned = index < selectedTrips.size() ? selectedTrips.get(index) : null;
					if (planned == null || !planned.getDestinationActivity().getType()
							.equals(executed.getDestinationActivity().getType())) {
						result.mismatchedTrips++;
					} else {
						diaryKm = diaryKilometres(entry.getKey(), planned);
					}
				}
				addTrip(result, executed.getLegsOnly(), diaryKm);
			}
		}
		return result;
	}

	private static Double diaryKilometres(Id<Person> personId, Trip planned) {
		Object value = planned.getDestinationActivity().getAttributes().getAttribute(LOOP_TRIP_DIARY_DISTANCE_ATTRIBUTE);
		if (value == null) {
			return null;
		}
		if (value instanceof Double km && Double.isFinite(km) && km > 0.0) {
			return km;
		}
		throw new IllegalStateException(String.format(Locale.ROOT,
				"Person %s: the activity %s carries %s=%s (%s); expected a positive finite java.lang.Double, the survey "
						+ "diary length in km written by the eqasim-bs plans writer.",
				personId, planned.getDestinationActivity().getType(), LOOP_TRIP_DIARY_DISTANCE_ATTRIBUTE, value,
				value.getClass().getName()));
	}

	private static void addTrip(Result result, List<Leg> legs, Double diaryKm) {
		double[] meters = new double[legs.size()];
		for (int index = 0; index < legs.size(); index++) {
			Route route = legs.get(index).getRoute();
			meters[index] = route == null ? Double.NaN : route.getDistance();
		}
		if (diaryKm != null && legs.size() > 0) {
			int main = mainLegIndex(legs);
			if (main < 0) {
				main = 0;
				result.correctedTripsWithoutRoutingModeLeg++;
			}
			double otherMeters = 0.0;
			double routeMeters = 0.0;
			for (int index = 0; index < legs.size(); index++) {
				if (Double.isFinite(meters[index])) {
					routeMeters += meters[index];
					if (index != main) {
						otherMeters += meters[index];
					}
				}
			}
			double diaryMeters = diaryKm * 1000.0;
			meters[main] = Math.max(0.0, diaryMeters - otherMeters);
			result.correctedTrips++;
			result.correctedTripDiaryMeters += diaryMeters;
			result.correctedTripRouteMeters += routeMeters;
		}
		double tripMeters = 0.0;
		for (int index = 0; index < legs.size(); index++) {
			tripMeters += meters[index];
			if (Double.isFinite(meters[index])) {
				result.legStatistics.accept(meters[index]);
				result.metersByMode.merge(legs.get(index).getMode(), meters[index], Double::sum);
			}
		}
		if (Double.isFinite(tripMeters)) {
			result.tripStatistics.accept(tripMeters);
		}
	}

	private static int mainLegIndex(List<Leg> legs) {
		for (int index = 0; index < legs.size(); index++) {
			Leg leg = legs.get(index);
			String routingMode = TripStructureUtils.getRoutingMode(leg);
			if (routingMode != null && routingMode.equals(leg.getMode())) {
				return index;
			}
		}
		return -1;
	}
}
