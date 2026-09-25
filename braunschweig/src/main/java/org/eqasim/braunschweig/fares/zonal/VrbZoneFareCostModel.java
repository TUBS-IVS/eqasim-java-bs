package org.eqasim.braunschweig.fares.zonal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

import org.eqasim.core.simulation.mode_choice.cost.CostModel;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import com.google.inject.Inject;

/**
 * VRB zone tariff 2026 for one routed PT trip (ADR-0133).
 *
 * <p>Rules, in priority order: no PT leg costs nothing; a traveller younger than the child minimum
 * age rides free; a line without a scope row is a counted fallback; a journey with a long-distance
 * ride pays the fare model's long-distance flat price, whatever the ticket (no Germany-wide or VRB
 * pass is valid on long-distance services; ADR-0133 D6); when every boarding and alighting
 * stop is zoned, flat holders pay nothing and everyone else pays the VRB single (a short trip for
 * one bus/tram ride of at most three stop intervals, otherwise the matrix price class of the first
 * boarding zone and the last alighting zone); when any stop is unzoned, national flat holders pay
 * nothing, rail legs are priced by Niedersachsentarif band on the ridden stop-to-stop distance
 * times the distance factor, and other journeys pay the external local single.
 *
 * <p>Ticket validity windows are not modelled: every trip is priced on its own. Every quote records
 * exactly one outcome in the {@link FareOutcomeCounter}. Cash is in euro cents; the eqasim
 * {@link CostModel} contract returns euros. Distances are Euclidean in the schedule CRS (EPSG:25832).
 */
public final class VrbZoneFareCostModel implements CostModel, FareQuoteSource {
	/** Stop facility attribute holding the VRB zone id, written by AddVrbTariffZoneInformation. */
	public static final String ZONE_ATTRIBUTE = "vrbTariffZone";
	/** Person attribute holding the population ticket category (eqasim-bs pt_subscription_type). */
	public static final String CATEGORY_ATTRIBUTE = "ptSubscriptionType";
	private static final String MODE_CLASS_RAIL = "rail";

	private final TransitSchedule schedule;
	private final VrbFareModel fares;
	private final PtLineScopes lineScopes;
	private final FareOutcomeCounter counter;

	@Inject
	public VrbZoneFareCostModel(TransitSchedule schedule, VrbFareModel fares, PtLineScopes lineScopes,
			FareOutcomeCounter counter) {
		this.schedule = schedule;
		this.fares = fares;
		this.lineScopes = lineScopes;
		this.counter = counter;
		requireKnownZones(schedule, fares);
	}

	/**
	 * Every zone id attached to a stop facility must be a zone of the fare model: a new or misread id would
	 * otherwise only surface later as counted vrb_pair_undefined_fallback quotes.
	 */
	private static void requireKnownZones(TransitSchedule schedule, VrbFareModel fares) {
		Set<String> unknown = new TreeSet<>();
		for (TransitStopFacility facility : schedule.getFacilities().values()) {
			String zone = zone(facility);
			if (zone != null && !fares.zones().contains(zone)) {
				unknown.add(zone);
			}
		}
		if (!unknown.isEmpty()) {
			throw new IllegalStateException("stop facilities carry " + ZONE_ATTRIBUTE + " values that the VRB fare model does"
					+ " not know: " + unknown + "; regenerate the zone attribution and the fare model from the same polygon layer");
		}
	}

	@Override
	public double calculateCost_MU(Person person, DiscreteModeChoiceTrip trip, List<? extends PlanElement> elements) {
		return quote(person, elements).cents() / 100.0;
	}

	@Override
	public FareQuote quote(Person person, List<? extends PlanElement> elements) {
		FareQuote quote = evaluate(person, elements);
		counter.record(quote.outcome());
		return quote;
	}

	@Override
	public FareQuote quoteWithoutCounting(Person person, List<? extends PlanElement> elements) {
		return evaluate(person, elements);
	}

	/** One PT leg as the tariff sees it: zones at both ends, line scope, ridden distance and stop intervals. */
	record Ride(String boardingZone, String alightingZone, Optional<PtLineScopes.Scope> scope, String transportMode,
			double distanceKm, int stopIntervals) {
		boolean zoned() {
			return boardingZone != null && alightingZone != null;
		}

		boolean rail() {
			return scope.map(value -> MODE_CLASS_RAIL.equals(value.modeClass()))
					.orElse(MODE_CLASS_RAIL.equals(transportMode));
		}
	}

	private FareQuote evaluate(Person person, List<? extends PlanElement> elements) {
		List<Ride> rides = rides(elements);
		if (rides.isEmpty()) {
			return new FareQuote(0, FareQuote.NO_PT_LEG, null);
		}
		Integer age = PersonUtils.getAge(person);
		if (age == null) {
			throw new IllegalStateException("person " + person.getId() + " has no age attribute; the VRB fare model needs it"
					+ " for the child rules (the synthetic population writes an age for every person)");
		}
		if (age < fares.childMinimumAge()) {
			return new FareQuote(0, FareQuote.CHILD_FREE, null);
		}
		boolean child = age <= fares.childMaximumAge();
		if (rides.stream().anyMatch(ride -> ride.scope().isEmpty())) {
			return fallback(FareQuote.LINE_SCOPE_MISSING);
		}
		if (rides.stream().anyMatch(ride -> PtLineScopes.SCOPE_LONG_DISTANCE.equals(ride.scope().get().tariffScope()))) {
			return new FareQuote(fares.longDistanceSingleCents(), FareQuote.LONG_DISTANCE_FLAT, null);
		}
		Object rawCategory = person.getAttributes().getAttribute(CATEGORY_ATTRIBUTE);
		String categoryIssue = null;
		VrbFareModel.Holder holder = VrbFareModel.Holder.NONE;
		if (rawCategory == null) {
			categoryIssue = FareQuote.CATEGORY_MISSING;
		} else {
			VrbFareModel.Holder known = fares.holder(rawCategory.toString());
			if (known == null) {
				categoryIssue = FareQuote.CATEGORY_UNKNOWN;
			} else {
				holder = known;
			}
		}
		FareQuote priced = price(rides, holder, child);
		// A missing or unknown category is priced as "no entitlement" but keeps its own outcome, so a
		// systematic attribute gap shows up in the fallback share instead of disappearing into the singles.
		return categoryIssue == null ? priced
				: new FareQuote(priced.cents(), categoryIssue, priced.priceClass(), priced.vrbCash());
	}

	private FareQuote price(List<Ride> rides, VrbFareModel.Holder holder, boolean child) {
		boolean insideVrb = rides.stream().allMatch(Ride::zoned);
		if (insideVrb) {
			if (holder == VrbFareModel.Holder.NATIONAL_FLAT) {
				return new FareQuote(0, FareQuote.VRB_FLAT_NATIONAL, null);
			}
			if (holder == VrbFareModel.Holder.VRB_FLAT) {
				return new FareQuote(0, FareQuote.VRB_FLAT_REGIONAL, null);
			}
			return vrbSingle(rides, child);
		}
		if (holder == VrbFareModel.Holder.NATIONAL_FLAT) {
			return new FareQuote(0, FareQuote.EXTERNAL_NATIONAL_FLAT, null);
		}
		// A journey that leaves the VRB is priced once as external; a VRB flat does not reduce it (ADR-0133 D7).
		double railKm = rides.stream().filter(Ride::rail).mapToDouble(Ride::distanceKm).sum();
		if (railKm > 0.0) {
			OptionalLong cents = fares.railBandCents(railKm * fares.distanceFactor(), child);
			return cents.isPresent() ? new FareQuote(cents.getAsLong(), FareQuote.EXTERNAL_RAIL_NT, null)
					: fallback(FareQuote.EXTERNAL_RAIL_BEYOND_BANDS);
		}
		return new FareQuote(fares.externalLocalSingleCents(), FareQuote.EXTERNAL_LOCAL_FLAT, null);
	}

	private FareQuote vrbSingle(List<Ride> rides, boolean child) {
		Optional<String> priceClass = fares.priceClass(rides.get(0).boardingZone(), rides.get(rides.size() - 1).alightingZone());
		if (priceClass.isEmpty()) {
			return fallback(FareQuote.VRB_PAIR_UNDEFINED_FALLBACK);
		}
		Ride only = rides.size() == 1 ? rides.get(0) : null;
		if (only != null && !only.rail() && only.stopIntervals() <= fares.shortTripMaximumStopIntervals()) {
			return new FareQuote(fares.shortTripCents(), FareQuote.VRB_SHORT_TRIP, priceClass.get());
		}
		return new FareQuote(fares.singleCents(priceClass.get(), child),
				child ? FareQuote.VRB_SINGLE_CHILD : FareQuote.VRB_SINGLE_ADULT, priceClass.get());
	}

	private FareQuote fallback(String outcome) {
		return new FareQuote(fares.fallbackCents(), outcome, null);
	}

	private List<Ride> rides(List<? extends PlanElement> elements) {
		List<Ride> rides = new ArrayList<>();
		for (PlanElement element : elements) {
			if (!(element instanceof Leg leg) || !(leg.getRoute() instanceof TransitPassengerRoute route)) {
				continue;
			}
			TransitLine line = schedule.getTransitLines().get(route.getLineId());
			if (line == null) {
				throw new IllegalStateException("routed PT leg references unknown transit line " + route.getLineId());
			}
			TransitRoute transitRoute = line.getRoutes().get(route.getRouteId());
			if (transitRoute == null) {
				throw new IllegalStateException("routed PT leg references unknown transit route " + route.getRouteId()
						+ " on line " + route.getLineId());
			}
			// On a loop route the boarding stop can occur twice before the alighting stop; the passenger boards
			// at the last occurrence, so the ridden distance and stop intervals start there.
			int egress = indexOf(transitRoute, route.getEgressStopId(), indexOf(transitRoute, route.getAccessStopId(), 0) + 1);
			int access = lastIndexBefore(transitRoute, route.getAccessStopId(), egress);
			double meters = 0.0;
			for (int index = access + 1; index <= egress; index++) {
				Coord before = transitRoute.getStops().get(index - 1).getStopFacility().getCoord();
				Coord after = transitRoute.getStops().get(index).getStopFacility().getCoord();
				meters += CoordUtils.calcEuclideanDistance(before, after);
			}
			rides.add(new Ride(zone(transitRoute.getStops().get(access).getStopFacility()),
					zone(transitRoute.getStops().get(egress).getStopFacility()), lineScopes.scope(line.getId()),
					transitRoute.getTransportMode(), meters / 1000.0, egress - access));
		}
		return rides;
	}

	private static int indexOf(TransitRoute route, Id<TransitStopFacility> facilityId, int from) {
		List<TransitRouteStop> stops = route.getStops();
		for (int index = from; index < stops.size(); index++) {
			if (stops.get(index).getStopFacility().getId().equals(facilityId)) {
				return index;
			}
		}
		throw new IllegalStateException("stop facility " + facilityId + " is not on transit route " + route.getId()
				+ " after position " + from);
	}

	private static int lastIndexBefore(TransitRoute route, Id<TransitStopFacility> facilityId, int before) {
		List<TransitRouteStop> stops = route.getStops();
		for (int index = before - 1; index >= 0; index--) {
			if (stops.get(index).getStopFacility().getId().equals(facilityId)) {
				return index;
			}
		}
		throw new IllegalStateException("stop facility " + facilityId + " is not on transit route " + route.getId()
				+ " before position " + before);
	}

	private static String zone(TransitStopFacility facility) {
		Object value = facility.getAttributes().getAttribute(ZONE_ATTRIBUTE);
		return value == null ? null : value.toString();
	}
}
