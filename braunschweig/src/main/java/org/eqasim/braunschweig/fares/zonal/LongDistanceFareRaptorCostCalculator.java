package org.eqasim.braunschweig.fares.zonal;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.population.PersonUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.Vehicles;

import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorInVehicleCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.RaptorInVehicleCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.RaptorParameters;

/**
 * SwissRailRaptor in-vehicle cost with the long-distance flat price (ADR-0133 D6): a ride on a
 * long-distance vehicle (DB Fernverkehr, Flix; tariff scope {@code long_distance}) costs the default
 * in-vehicle cost plus the fare converted into equivalent in-vehicle seconds, valued at the ride's
 * marginal utility of travel time. The router therefore takes a regional train instead of an ICE unless
 * the time saving is worth the fare, as the mode choice values it.
 *
 * <p>SwissRailRaptorCore calls the calculator once per ride, with the time from boarding to the candidate
 * alighting stop, and adds the result to the cost at boarding, so the surcharge counts once per ride.
 * Travellers younger than the fare model's child minimum age ride free and get no surcharge.
 * ASSUMPTION: the surcharge is the full flat price, exact for holders of a Germany-wide or VRB pass
 * (their regional alternative is free) and an upper bound for everyone else (their regional alternative
 * costs a VRB single), because the router cannot see the zone fare of the whole journey.
 */
public final class LongDistanceFareRaptorCostCalculator implements RaptorInVehicleCostCalculator {
	private static final Logger LOGGER = LogManager.getLogger(LongDistanceFareRaptorCostCalculator.class);

	private final RaptorInVehicleCostCalculator delegate = new DefaultRaptorInVehicleCostCalculator();
	private final Set<Id<Vehicle>> longDistanceVehicles;
	private final double surchargeSeconds;
	private final int childMinimumAge;

	private LongDistanceFareRaptorCostCalculator(Set<Id<Vehicle>> longDistanceVehicles, double surchargeSeconds,
			int childMinimumAge) {
		this.longDistanceVehicles = Set.copyOf(longDistanceVehicles);
		this.surchargeSeconds = surchargeSeconds;
		this.childMinimumAge = childMinimumAge;
	}

	/**
	 * Collects the vehicles of every departure of a long-distance line. A departure without a vehicle, or
	 * with a vehicle missing from the transit vehicles, fails: SwissRailRaptor would pass no vehicle for it
	 * and the surcharge would silently never apply.
	 */
	public static LongDistanceFareRaptorCostCalculator create(TransitSchedule schedule, Vehicles transitVehicles,
			PtLineScopes lineScopes, double surchargeSeconds, int childMinimumAge) {
		if (!(surchargeSeconds >= 0.0)) {
			throw new IllegalArgumentException("long-distance routing surcharge must be >= 0 seconds, got " + surchargeSeconds);
		}
		Set<Id<Vehicle>> vehicles = new HashSet<>();
		int lines = 0;
		for (TransitLine line : schedule.getTransitLines().values()) {
			boolean longDistance = lineScopes.scope(line.getId())
					.map(scope -> PtLineScopes.SCOPE_LONG_DISTANCE.equals(scope.tariffScope())).orElse(false);
			if (!longDistance) {
				continue;
			}
			lines++;
			for (TransitRoute route : line.getRoutes().values()) {
				for (Departure departure : route.getDepartures().values()) {
					Id<Vehicle> vehicleId = departure.getVehicleId();
					if (vehicleId == null || !transitVehicles.getVehicles().containsKey(vehicleId)) {
						throw new IllegalStateException("departure " + departure.getId() + " of long-distance line "
								+ line.getId() + " has no transit vehicle (" + vehicleId + "); the routing surcharge could"
								+ " not recognise it. Load the transit vehicles or set vrbFare.longDistanceRoutingSurchargeEnabled=false");
					}
					vehicles.add(vehicleId);
				}
			}
		}
		LOGGER.info(String.format(Locale.ROOT,
				"[vrb-fares] long-distance routing surcharge: %.0f s (%.1f min) of in-vehicle time on %d vehicles of %d lines",
				surchargeSeconds, surchargeSeconds / 60.0, vehicles.size(), lines));
		return new LongDistanceFareRaptorCostCalculator(vehicles, surchargeSeconds, childMinimumAge);
	}

	/**
	 * The fare in equivalent PT in-vehicle seconds at the mode choice's value of time: cost / (beta_time /
	 * beta_cost). ASSUMPTION: the reference person of the cost term (reference distance and income, where
	 * both interaction factors are 1); the model's value of time grows with distance, so long journeys get
	 * a somewhat larger surcharge than their own valuation.
	 *
	 * @param fareCents the flat price in euro cents
	 * @param betaInVehicleTimePerMinute PT in-vehicle time utility per minute (negative)
	 * @param betaCostPerEuro cost utility per euro (negative)
	 */
	public static double surchargeSeconds(long fareCents, double betaInVehicleTimePerMinute, double betaCostPerEuro) {
		if (!(betaInVehicleTimePerMinute < 0.0) || !(betaCostPerEuro < 0.0)) {
			throw new IllegalArgumentException("the value of time needs negative time and cost utilities, got "
					+ betaInVehicleTimePerMinute + " per minute and " + betaCostPerEuro + " per euro");
		}
		double euroPerSecond = betaInVehicleTimePerMinute / 60.0 / betaCostPerEuro;
		return fareCents / 100.0 / euroPerSecond;
	}

	@Override
	public double getInVehicleCost(double inVehicleTime, double marginalUtility_utl_s, Person person, Vehicle vehicle,
			RaptorParameters parameters, RouteSegmentIterator iterator) {
		double cost = delegate.getInVehicleCost(inVehicleTime, marginalUtility_utl_s, person, vehicle, parameters, iterator);
		if (vehicle != null && longDistanceVehicles.contains(vehicle.getId()) && pays(person)) {
			cost += surchargeSeconds * -marginalUtility_utl_s;
		}
		return cost;
	}

	private boolean pays(Person person) {
		if (person == null) {
			return true;
		}
		Integer age = PersonUtils.getAge(person);
		return age == null || age >= childMinimumAge;
	}

	Set<Id<Vehicle>> longDistanceVehicles() {
		return longDistanceVehicles;
	}
}
