package org.eqasim.braunschweig.mode_choice.utilities.estimators;

import java.util.List;

import org.eqasim.braunschweig.fares.zonal.FareOutcomeCounter;
import org.eqasim.braunschweig.fares.zonal.FareQuote;
import org.eqasim.braunschweig.fares.zonal.FareQuoteSource;
import org.eqasim.braunschweig.fares.zonal.VrbFareConfigGroup;
import org.eqasim.braunschweig.fares.zonal.VrbFareModel;
import org.eqasim.braunschweig.mode_choice.parameters.BraunschweigModeParameters;
import org.eqasim.braunschweig.mode_choice.utilities.predictors.BraunschweigPersonPredictor;
import org.eqasim.braunschweig.mode_choice.utilities.predictors.BraunschweigPtPredictor;
import org.eqasim.core.simulation.mode_choice.cost.CostModel;
import org.eqasim.core.simulation.mode_choice.utilities.PrefixAwareUtilityEstimator;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.RoutedTripCandidate;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;

import com.google.inject.Inject;
import com.google.inject.name.Named;

/**
 * PT utility with the VRB zone fare and the day-ticket cap (ADR-0133 D9).
 *
 * <p>A person's VRB cash for the day is min(sum of VRB singles, day ticket of the highest price class
 * used). The monetary term of the current trip is the increase of that quantity over the PT candidates
 * DMC passes as prefix (earlier selected tours plus the current tour's earlier trips). Earlier trips are
 * re-priced without counting, so every trip enters the outcome report once. Flat, external and fallback
 * outcomes are never capped. With the cap disabled the plain quote is used. ASSUMPTION: the traveller
 * anticipates the trips of the candidate tour and of the day so far, not later tours.
 */
public final class VrbZoneFarePtUtilityEstimator extends BraunschweigPtUtilityEstimator
		implements PrefixAwareUtilityEstimator {
	private final FareQuoteSource fares;
	private final VrbFareModel model;
	private final VrbFareConfigGroup config;
	private final FareOutcomeCounter counter;

	@Inject
	public VrbZoneFarePtUtilityEstimator(BraunschweigModeParameters parameters, BraunschweigPtPredictor ptPredictor,
			BraunschweigPersonPredictor personPredictor, @Named("pt") CostModel costModel, FareQuoteSource fares,
			VrbFareModel model, VrbFareConfigGroup config, FareOutcomeCounter counter) {
		super(parameters, ptPredictor, personPredictor, costModel);
		this.fares = fares;
		this.model = model;
		this.config = config;
		this.counter = counter;
	}

	@Override
	public double estimateUtility(Person person, DiscreteModeChoiceTrip trip, List<? extends PlanElement> elements) {
		return estimateUtility(person, trip, elements, List.of());
	}

	@Override
	public double estimateUtility(Person person, DiscreteModeChoiceTrip trip, List<? extends PlanElement> elements,
			List<TripCandidate> previousTrips) {
		return estimateUtilityAtCost(person, trip, elements, marginalCostEur(person, elements, previousTrips));
	}

	/** Cash in EUR this trip adds to the person's day under the cap; package-visible for the unit test. */
	double marginalCostEur(Person person, List<? extends PlanElement> elements, List<TripCandidate> previousTrips) {
		FareQuote current = fares.quote(person, elements);
		if (!config.isDayTicketCapEnabled() || !current.isVrbCash()) {
			return current.cents() / 100.0;
		}
		long singlesBefore = 0;
		String classBefore = null;
		for (TripCandidate candidate : previousTrips) {
			if (!"pt".equals(candidate.getMode()) || !(candidate instanceof RoutedTripCandidate routed)) {
				continue;
			}
			FareQuote earlier = fares.quoteWithoutCounting(person, routed.getRoutedPlanElements());
			if (!earlier.isVrbCash()) {
				continue;
			}
			singlesBefore += earlier.cents();
			classBefore = classBefore == null ? earlier.priceClass() : model.higherClass(classBefore, earlier.priceClass());
		}
		long cashBefore = classBefore == null ? 0 : Math.min(singlesBefore, model.dayTicketCents(classBefore));
		String classAfter = classBefore == null ? current.priceClass() : model.higherClass(classBefore, current.priceClass());
		long singlesAfter = singlesBefore + current.cents();
		long cashAfter = Math.min(singlesAfter, model.dayTicketCents(classAfter));
		if (cashAfter < singlesAfter) {
			counter.record(FareQuote.DAY_TICKET_CAP_APPLIED);
		}
		return Math.max(0, cashAfter - cashBefore) / 100.0;
	}
}
