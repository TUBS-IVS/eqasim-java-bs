package org.eqasim.braunschweig.mode_choice.utilities.estimators;

import java.util.ArrayList;
import java.util.List;

import org.eqasim.braunschweig.fares.zonal.DayTicketCap;
import org.eqasim.braunschweig.fares.zonal.DayTicketCapAdjustment;
import org.eqasim.braunschweig.fares.zonal.FareOutcomeCounter;
import org.eqasim.braunschweig.fares.zonal.FareQuote;
import org.eqasim.braunschweig.fares.zonal.FareQuoteSource;
import org.eqasim.braunschweig.fares.zonal.VrbFareModel;
import org.eqasim.braunschweig.mode_choice.parameters.BraunschweigModeParameters;
import org.eqasim.braunschweig.mode_choice.utilities.predictors.BraunschweigPersonPredictor;
import org.eqasim.braunschweig.mode_choice.utilities.predictors.BraunschweigPtPredictor;
import org.eqasim.core.simulation.mode_choice.cost.CostModel;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.RoutedTripCandidate;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;

import com.google.inject.Inject;
import com.google.inject.name.Named;

/**
 * PT utility with the VRB zone fare and its day-ticket cap correction (ADR-0133 D9).
 *
 * <p>{@link #estimateUtility} is the Braunschweig PT utility at the trip's own fare from the VRB zone
 * fare cost model, so DMC may cache it. {@link #utilityAdjustment} then corrects a cached estimate for the
 * day so far: a person's VRB cash for the day is the cheapest of all singles or one day ticket plus the
 * singles of the trips above its price class ({@link DayTicketCap}), and the trip's fare becomes the
 * increase of that cash over the PT candidates of the prefix (earlier selected tours plus the current
 * tour's earlier trips). Because the monetary term is the only term that depends on the fare, the
 * correction is the monetary utility at that marginal fare minus the one at the single.
 *
 * <p>The correction re-prices trips without counting: the trip's own quote was counted when its utility
 * was estimated. It records {@link FareQuote#DAY_TICKET_CAP_APPLIED} once per evaluated tour-chain position
 * where the cap binds, so that label counts evaluations, not travellers. Flat, external and fallback
 * outcomes are never capped. ASSUMPTION: the traveller anticipates the trips of the candidate tour and of
 * the day so far, not later tours.
 */
public final class VrbZoneFarePtUtilityEstimator extends BraunschweigPtUtilityEstimator implements DayTicketCapAdjustment {
	private final FareQuoteSource fares;
	private final VrbFareModel model;
	private final FareOutcomeCounter counter;

	@Inject
	public VrbZoneFarePtUtilityEstimator(BraunschweigModeParameters parameters, BraunschweigPtPredictor ptPredictor,
			BraunschweigPersonPredictor personPredictor, @Named("pt") CostModel costModel, FareQuoteSource fares,
			VrbFareModel model, FareOutcomeCounter counter) {
		super(parameters, ptPredictor, personPredictor, costModel);
		this.fares = fares;
		this.model = model;
		this.counter = counter;
	}

	@Override
	public double utilityAdjustment(Person person, DiscreteModeChoiceTrip trip, List<? extends PlanElement> elements,
			List<TripCandidate> previousTrips) {
		FareQuote current = fares.quoteWithoutCounting(person, elements);
		if (!current.vrbCash()) {
			return 0.0;
		}
		long marginal = marginalCents(person, current, previousTrips);
		if (marginal == current.cents()) {
			return 0.0;
		}
		counter.record(FareQuote.DAY_TICKET_CAP_APPLIED);
		return estimateMonetaryCostUtilityAtCost(person, trip, elements, marginal / 100.0)
				- estimateMonetaryCostUtilityAtCost(person, trip, elements, current.cents() / 100.0);
	}

	/** Cash in euro cents a VRB cash trip adds to the person's day; package-visible for the unit test. */
	long marginalCents(Person person, FareQuote current, List<TripCandidate> previousTrips) {
		List<FareQuote> before = new ArrayList<>();
		for (TripCandidate candidate : previousTrips) {
			if (!TransportMode.pt.equals(candidate.getMode()) || !(candidate instanceof RoutedTripCandidate routed)) {
				continue;
			}
			FareQuote earlier = fares.quoteWithoutCounting(person, routed.getRoutedPlanElements());
			if (earlier.vrbCash()) {
				before.add(earlier);
			}
		}
		return DayTicketCap.marginalCents(model, before, current);
	}
}
