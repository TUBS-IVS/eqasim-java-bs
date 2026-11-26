package org.eqasim.core.simulation.mode_choice.filters;

import org.eqasim.core.scenario.routing.PlanRouter;
import org.matsim.contribs.discrete_mode_choice.components.tour_finder.TourFinder;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceModel;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.EfficientTourBasedModel;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourBasedModel;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourFilter;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TripFilter;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripBasedModel;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.router.TripRouter;
import org.matsim.facilities.ActivityFacilities;

import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.Singleton;

/**
 * Handles the trips the discrete mode choice does not re-choose (eqasim-bs#678). Overrides the contrib's
 * {@code DiscreteModeChoiceModel} binding with the configured model wrapped in {@link KeepFilteredTripsModel}, so the
 * MATSim loop's mode choice replanning (the contrib's DiscreteModeChoice strategy) and the standalone mode choice both
 * use it. What the kept trips get is the bound {@link KeptTripElements}: routed again with eqasim's routing in the loop
 * (bound here), unchanged in the standalone mode choice (overridden there). Must be installed after the contrib's
 * {@code DiscreteModeChoiceModule}, as an overriding module (the {@code EqasimConfigurator} registers it after
 * {@code EqasimModeChoiceModule}).
 */
public class FilteredTripsModule extends AbstractModule {
	@Override
	public void install() {
		addControllerListenerBinding().to(FilteredTripCounter.class);
	}

	@Provides
	@Singleton
	public FilteredTripCounter provideFilteredTripCounter() {
		return new FilteredTripCounter();
	}

	/**
	 * The trips the configured model does not re-choose: those of tours (tour-based models) or the trips (trip-based
	 * model) its filter rejects. Uses the model's own tour finder and filter. The filters must not depend on trip
	 * departure times, which the model sets before filtering and the finder does not; eqasim's OutsideFilter and the
	 * contrib's TourLengthFilter satisfy this (see {@link FilteredTripFinder}).
	 */
	@Provides
	public FilteredTripFinder provideFilteredTripFinder(DiscreteModeChoiceConfigGroup dmcConfig,
			Provider<TourFinder> tourFinder, Provider<TourFilter> tourFilter, Provider<TripFilter> tripFilter) {
		switch (dmcConfig.getModelType()) {
		case Tour:
		case EfficientTour:
			return FilteredTripFinder.forTours(tourFinder.get(), tourFilter.get());
		case Trip:
			return FilteredTripFinder.forTrips(tripFilter.get());
		default:
			throw new IllegalStateException("Unknown discrete mode choice model type " + dmcConfig.getModelType());
		}
	}

	/**
	 * The MATSim loop's choice for the trips the model does not re-choose: route them again with eqasim's routing (owner
	 * decision 2026-11-26, see {@link KeptTripElements}). The standalone mode choice overrides this binding with
	 * {@link KeptTripElements#unchanged()}. Not a singleton: one {@link PlanRouter} per model instance, i.e. per
	 * replanning thread, created here, together with the model, on the thread that asks for the model (the contrib's
	 * strategy does so on the main thread before its replanning threads start; see {@link KeptTripElements.Rerouted}
	 * for why this matters for reproducibility).
	 */
	@Provides
	public KeptTripElements provideKeptTripElements(ActivityFacilities facilities, Provider<TripRouter> tripRouter,
			FilteredTripCounter counter) {
		// PlanRouter is not bound in the simulation injector (it disables just-in-time bindings); a fresh TripRouter
		// per instance, as the contrib's strategy takes one per replanning thread
		return KeptTripElements.rerouted(new PlanRouter(facilities, tripRouter.get()), counter);
	}

	/** The configured model (the same choice of model type as the contrib's binding), wrapped. Not a singleton. */
	@Provides
	public DiscreteModeChoiceModel provideDiscreteModeChoiceModel(DiscreteModeChoiceConfigGroup dmcConfig,
			Provider<TourBasedModel> tourBasedModel, Provider<EfficientTourBasedModel> efficientTourBasedModel,
			Provider<TripBasedModel> tripBasedModel, FilteredTripFinder filteredTripFinder,
			KeptTripElements keptTripElements, FilteredTripCounter counter) {
		DiscreteModeChoiceModel model;
		switch (dmcConfig.getModelType()) {
		case Tour:
			model = tourBasedModel.get();
			break;
		case EfficientTour:
			model = efficientTourBasedModel.get();
			break;
		case Trip:
			model = tripBasedModel.get();
			break;
		default:
			throw new IllegalStateException("Unknown discrete mode choice model type " + dmcConfig.getModelType());
		}
		return new KeepFilteredTripsModel(model, filteredTripFinder, keptTripElements, counter);
	}
}
