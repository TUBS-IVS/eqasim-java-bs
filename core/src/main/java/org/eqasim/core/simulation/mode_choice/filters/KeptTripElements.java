package org.eqasim.core.simulation.mode_choice.filters;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.eqasim.core.scenario.routing.PlanRouter;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;

/**
 * The elements a trip that the mode choice does not re-choose goes back into the plan with ({@link KeepFilteredTripsModel},
 * eqasim-bs#678). Two strategies, chosen by the binding (owner decision 2026-11-26):
 * <ul>
 * <li>{@link #rerouted(PlanRouter)}, bound by {@link FilteredTripsModule} for the MATSim loop: the trip is routed again
 * with eqasim's own {@link PlanRouter#routeTrip}. In the loop this re-estimation is the only route update of persons
 * that have no ReRoute strategy (the residents), so freezing the routes would stop car routes reacting to congestion.
 * Unlike the contrib's plain trip router, eqasim's routing replaces a walk-only transit route by a stamped walk and keeps
 * the walk-fallback stamp of a trip that is still walk-only.</li>
 * <li>{@link #unchanged()}, bound by the standalone mode choice: the trip's own elements, the very same objects. That
 * run happens once before iteration 0 under free flow, where a new route would only replace the pipeline's routing with
 * a worse one; the trip leaves exactly as it came in.</li>
 * </ul>
 */
public interface KeptTripElements {
	List<PlanElement> elements(Person person, DiscreteModeChoiceTrip trip);

	/** The trip's own elements, unchanged (standalone mode choice). */
	static KeptTripElements unchanged() {
		return (person, trip) -> new ArrayList<>(trip.getInitialElements());
	}

	/**
	 * The trip routed again with eqasim's routing (MATSim loop), departing at the time the mode choice model estimated
	 * for it ({@link DiscreteModeChoiceTrip#getDepartureTime()}, set for every trip before the filter runs), the time at
	 * which the contrib routed it before. One instance per replanning thread: a {@link PlanRouter} is not thread-safe.
	 * The router is created on the first kept trip only: creating a trip router draws from MATSim's random numbers, so an
	 * eager router would change the results of runs in which no trip is kept.
	 */
	static KeptTripElements rerouted(Supplier<PlanRouter> planRouterFactory) {
		return new KeptTripElements() {
			private PlanRouter planRouter;

			@Override
			public List<PlanElement> elements(Person person, DiscreteModeChoiceTrip trip) {
				if (planRouter == null) {
					planRouter = planRouterFactory.get();
				}
				return new ArrayList<>(planRouter.routeTrip(person, trip.getOriginActivity(),
						trip.getDestinationActivity(), trip.getDepartureTime(), trip.getInitialElements(),
						trip.getTripAttributes()));
			}
		};
	}
}
