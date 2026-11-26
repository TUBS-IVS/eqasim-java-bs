package org.eqasim.core.simulation.mode_choice.filters;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eqasim.core.scenario.routing.PlanRouter;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;

/**
 * The elements a trip that the mode choice does not re-choose goes back into the plan with ({@link KeepFilteredTripsModel},
 * eqasim-bs#678). Two strategies, chosen by the binding (owner decision 2026-11-26):
 * <ul>
 * <li>{@link Rerouted}, bound by {@link FilteredTripsModule} for the MATSim loop: the trip is routed again with eqasim's
 * own {@link PlanRouter#routeTrip}. In the loop this re-estimation is the only route update of persons that have no
 * ReRoute strategy (the residents), so freezing the routes would stop car routes reacting to congestion. Unlike the
 * contrib's plain trip router, eqasim's routing replaces a walk-only transit route by a stamped walk and keeps the
 * walk-fallback stamp of a trip that is still walk-only. Both outcomes are counted in the {@link FilteredTripCounter}.</li>
 * <li>{@link Unchanged}, bound by the standalone mode choice: the trip's own elements, the very same objects. That run
 * happens once before iteration 0 under free flow, where a new route would only replace the pipeline's routing with a
 * worse one; the trip leaves exactly as it came in.</li>
 * </ul>
 */
public interface KeptTripElements {
	List<PlanElement> elements(Person person, DiscreteModeChoiceTrip trip);

	/** The trip's own elements, unchanged (standalone mode choice). */
	static KeptTripElements unchanged() {
		return new Unchanged();
	}

	/** The trip routed again with eqasim's routing (MATSim loop), see {@link Rerouted}. */
	static KeptTripElements rerouted(PlanRouter planRouter, FilteredTripCounter counter) {
		return new Rerouted(planRouter, counter);
	}

	/** The trip's own elements, unchanged. */
	final class Unchanged implements KeptTripElements {
		@Override
		public List<PlanElement> elements(Person person, DiscreteModeChoiceTrip trip) {
			return new ArrayList<>(trip.getInitialElements());
		}
	}

	/**
	 * The trip routed again with eqasim's routing, departing at the time the mode choice model estimated for it
	 * ({@link DiscreteModeChoiceTrip#getDepartureTime()}, set for every trip before the filter runs), the time at which
	 * the contrib routed it before. One instance per model instance, i.e. per replanning thread: a {@link PlanRouter} is
	 * not thread-safe. The router must be created when the model is created, which the contrib does on the main thread
	 * before the replanning threads start: creating a trip router draws from MATSim's random numbers (the randomized
	 * car disutility seeds from {@code MatsimRandom.getLocalInstance()}), so creating it inside a worker thread would
	 * make the routes depend on thread timing.
	 */
	final class Rerouted implements KeptTripElements {
		private final PlanRouter planRouter;
		private final FilteredTripCounter counter;

		Rerouted(PlanRouter planRouter, FilteredTripCounter counter) {
			this.planRouter = planRouter;
			this.counter = counter;
		}

		@Override
		public List<PlanElement> elements(Person person, DiscreteModeChoiceTrip trip) {
			int fallbacksBefore = sum(planRouter.getWalkFallbackCounts());
			int keptStampsBefore = sum(planRouter.getKeptWalkFallbackCounts());

			List<PlanElement> elements = new ArrayList<>(planRouter.routeTrip(person, trip.getOriginActivity(),
					trip.getDestinationActivity(), trip.getDepartureTime(), trip.getInitialElements(),
					trip.getTripAttributes()));

			counter.addRerouted(sum(planRouter.getWalkFallbackCounts()) - fallbacksBefore,
					sum(planRouter.getKeptWalkFallbackCounts()) - keptStampsBefore);
			return elements;
		}

		private static int sum(Map<String, Integer> counts) {
			return counts.values().stream().mapToInt(Integer::intValue).sum();
		}
	}
}
