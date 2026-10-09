package org.eqasim.braunschweig.parking;

import java.util.List;
import java.util.Locale;

import org.eqasim.braunschweig.mode_choice.parameters.BraunschweigCostParameters;
import org.eqasim.core.simulation.mode_choice.cost.AbstractCostModel;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.core.utils.timing.TimeTracker;

import com.google.inject.Inject;

/**
 * Car cost model of the zone-based parking costs (design "parking cost zones", sections 3.2, 3.3 and 5.2; eqasim-bs
 * issue #436), bound under BraunschweigModeChoiceModule.ZONE_PARKING_CAR_COST_MODEL_NAME when the braunschweigParking
 * module is enabled. The cost of one car trip in euros is the driving cost {@code carCost_EUR_km} times the in-vehicle
 * car distance plus the parking cost of the stay at the trip's destination, priced by {@link ParkingCostCalculator} in
 * euro cents from the tariff of the destination's zone. Nothing else changes: the car utility estimator applies its
 * income- and distance-elastic monetary coefficient to the returned euros, and car passengers do not use this model
 * (they pay nothing, as before).
 *
 * <p>Inputs, written by the eqasim-bs plans writer:
 * <ul>
 * <li>activity attribute {@value #ZONE_ATTRIBUTE} ({@code String}): the parking zone the destination lies in; absent
 * means outside every zone, where parking is free (assumption Z1, outcome NO_ZONE);</li>
 * <li>activity attribute {@value #FREE_ATTRIBUTE} ({@code Boolean}): the employer or institution provides free
 * parking; absent means false;</li>
 * <li>person attribute {@value #RESIDENT_ATTRIBUTE} ({@code String}): the resident zone containing the person's home;
 * the person is a resident of the destination zone when both ids are equal (assumption R1);</li>
 * <li>activity attribute {@value #DISTRICT_ATTRIBUTE} ({@code String}) and person attribute
 * {@value #RESIDENT_DISTRICT_ATTRIBUTE} ({@code String}): the resident parking district the destination lies in and the
 * one of the person's home, both absent where there is none; the stay is a stay in the person's own district when both
 * are set and equal (rule R2, spec Amendment C3), which frees it where the destination zone honours resident permits;</li>
 * <li>purpose: the destination's activity type (home, work, education, shop, ...).</li>
 * </ul>
 *
 * <p>Stay, as in BraunschweigCarCostModel: a TimeTracker starts at the trip departure and adds the trip elements, so the
 * arrival is the end of the whole trip (after the egress walk; the parked duration is approximated by the activity
 * stay); the departure is the end of the destination activity under the run's TimeInterpretation. The destination is
 * terminal when it is the last element of the person's selected plan; a terminal stay departs by the terminal-stay rule
 * (assumption T1, {@link ParkingCostCalculator#terminalDeparture_s}). {@code departure >= arrival} holds by
 * construction: the terminal rule never departs before the arrival, and the eqasim time interpretation
 * (plans.tripDurationHandling = shiftActivityEndTimes, set by GenerateConfig) never ends an activity before the car
 * arrives. A time interpretation that does (ignoreDelays, endTimeOnly) fails loudly instead of pricing a negative stay.
 *
 * <p>Minimum parked duration (assumption L1, {@code braunschweigParking.minimumStayMinutes}, 0 when absent): the stay is
 * priced from its arrival to {@link ParkingCostCalculator#minimumStayDeparture_s}, so every priced stay lasts at least
 * the minimum, terminal stays included (after rule T1). Under shiftActivityEndTimes a car that arrives after the planned
 * end of its activity has a stay of length 0, which the tariff rules alone price as free. The minimum is applied before
 * every tariff rule and changes the price only: the plan and the simulated timing stay as they are.
 *
 * <p>Garage options (spec Amendments E and H of parking cost zones v2): with a tariff model of schema 3 or 4 whose
 * {@code garage_decay_m} or {@code garage_decay_commute_m} is above 0 (the latter governs the purposes work and education,
 * the former every other purpose, {@link GarageParkingCostCalculator#decayMetersForPurpose}; a schema-3 model has one decay
 * for every purpose), a stay with a purpose whose decay is above 0 in a street or resident zone that passes the early rules
 * is priced by
 * {@link GarageParkingCostCalculator} as the probability-weighted mean over the street and the priced garages within the
 * maximum distance of the activity coordinate (EPSG:25832; no new plan attribute, outcome PAID_EXPECTED); every other stay,
 * and every stay of a model with the options off, is priced by {@link ParkingCostCalculator} as before. The garages are found
 * through the grid index of {@link GarageOptionModel}, and only for a stay that reaches the mixture, so the options cost
 * nothing for a stay decided by an early rule. The expected cost is deterministic: the car utility stays stable across the
 * mode-choice evaluations, and a stay is priced the same whichever thread evaluates it.
 *
 * <p>Every call records exactly one outcome in the {@link ParkingOutcomeCounter}, NO_ZONE included, together with the zone
 * of the destination (none for NO_ZONE) and the purpose of the stay, so the per-iteration report shows which rule priced
 * the car trips, where and for what, and counts the priced stays the minimum extended; a failing call records nothing.
 * An unknown zone id (in either zone attribute), an unknown district id (in either district attribute) and a mistyped
 * attribute raise IllegalStateException in the one format of {@link ParkingAttributes}, which reads all five attributes for
 * this model, the parking search time and the startup check: it names the owner (the person, the plan and the activity for
 * an activity attribute, the person for a person attribute), the attribute and the offending value; a non-terminal activity
 * without an end and an activity ending before the arrival raise it naming the person and the activity; a routed trip
 * element without a defined end raises it naming the person, the index and type of the element and the car trip.
 * {@link ParkingPopulationCheck} checks the attributes of every plan once at the controller start already.
 *
 * <p>Free of side effects apart from the counter: the plan, its activities and the trip are only read. Thread-safe:
 * the state is immutable and every call uses its own TimeTracker; the counter is thread-safe.
 */
public final class ZoneParkingCarCostModel extends AbstractCostModel {
	/** Activity attribute (String): id of the parking zone the activity lies in; absent outside every zone. */
	public static final String ZONE_ATTRIBUTE = "parkingZone";

	/** Activity attribute (Boolean): the employer or institution provides free parking; absent means false. */
	public static final String FREE_ATTRIBUTE = "parkingFree";

	/** Person attribute (String): id of the resident parking zone containing the person's home; absent: no zone. */
	public static final String RESIDENT_ATTRIBUTE = "residentParkingZone";

	/** Activity attribute (String): id of the resident parking district the activity lies in; absent: in no district. */
	public static final String DISTRICT_ATTRIBUTE = "parkingDistrict";

	/** Person attribute (String): id of the resident parking district of the person's home; absent: in no district. */
	public static final String RESIDENT_DISTRICT_ATTRIBUTE = "residentParkingDistrict";

	private static final ParkingPrice NO_ZONE = new ParkingPrice(new ParkingCostCalculator.Result(0, ParkingOutcome.NO_ZONE),
			null, false, 0.0, 0);
	private static final double CENTS_PER_EURO = 100.0;

	private final BraunschweigCostParameters costParameters;
	private final TimeInterpretation timeInterpretation;
	private final ParkingTariffs tariffs;
	private final ParkingOutcomeCounter counter;
	private final String tariffsPath;
	private final int minimumStay_s;
	private final GarageOptionModel garageOptions;

	/** The simulated car stay at the destination in simulation seconds: [arrival_s, departure_s). */
	private record Stay(double arrival_s, double departure_s) {
	}

	/**
	 * The parking price of one car trip, the zone of the destination (null outside every zone), whether the minimum
	 * parked duration (assumption L1) extended the priced interval beyond the simulated stay, and the garage options of the
	 * stay: the sum of the garage probabilities (0 where no garage acted) and the number of garages within the maximum
	 * distance.
	 */
	private record ParkingPrice(ParkingCostCalculator.Result result, String zoneId, boolean extendedByMinimumStay,
			double garageProbability, int garagesInRange) {
	}

	@Inject
	public ZoneParkingCarCostModel(BraunschweigCostParameters costParameters, TimeInterpretation timeInterpretation,
			ParkingTariffs tariffs, ParkingOutcomeCounter counter, ParkingConfigGroup parking) {
		super(TransportMode.car);
		this.costParameters = costParameters;
		this.timeInterpretation = timeInterpretation;
		this.tariffs = tariffs;
		this.counter = counter;
		this.tariffsPath = parking.getTariffsPath();
		this.minimumStay_s = parking.minimumStay_s();
		this.garageOptions = tariffs.garageOptions();
	}

	/**
	 * Driving cost plus the parking cost at the destination, in euros; records the parking outcome and, when the minimum
	 * parked duration extended the priced stay, the extension.
	 */
	@Override
	public double calculateCost_MU(Person person, DiscreteModeChoiceTrip trip, List<? extends PlanElement> elements) {
		double drivingCost_EUR = costParameters.carCost_EUR_km * getInVehicleDistance_km(elements);
		ParkingPrice parking = parkingCost(person, trip, elements);
		counter.record(parking.result().outcome(), parking.zoneId(), trip.getDestinationActivity().getType(),
				parking.garageProbability(), parking.garagesInRange());
		if (parking.extendedByMinimumStay()) {
			counter.recordMinimumStayExtension();
		}
		return drivingCost_EUR + parking.result().cents() / CENTS_PER_EURO;
	}

	private ParkingPrice parkingCost(Person person, DiscreteModeChoiceTrip trip, List<? extends PlanElement> elements) {
		Activity destination = trip.getDestinationActivity();
		ZoneTariff tariff = ParkingAttributes.zone(tariffs, tariffsPath, person, destination);
		if (tariff == null) {
			return NO_ZONE;
		}
		String zoneId = tariff.zoneId();
		boolean parkingFree = ParkingAttributes.parkingFree(person, destination);
		ZoneTariff residentZone = ParkingAttributes.residentZone(tariffs, tariffsPath, person);
		boolean residentOfZone = residentZone != null && zoneId.equals(residentZone.zoneId());
		boolean residentOfDistrict = residentOfDistrict(person, destination);
		Stay stay = stay(person, trip, elements, tariff);
		// Assumption L1: priced for at least the minimum parked duration, before every tariff rule.
		double pricedDeparture_s = ParkingCostCalculator.minimumStayDeparture_s(stay.arrival_s(), stay.departure_s(),
				minimumStay_s);
		boolean extendedByMinimumStay = pricedDeparture_s > stay.departure_s();
		if (!garageOptions.isActive()) {
			// Garage options off (schema 1 or 2 model, or both decays 0): the zone products price the stay, no option code
			// runs.
			ParkingCostCalculator.Result result = ParkingCostCalculator.cents(tariff, stay.arrival_s(), pricedDeparture_s,
					destination.getType(), parkingFree, residentOfZone, residentOfDistrict);
			return new ParkingPrice(result, zoneId, extendedByMinimumStay, 0.0, 0);
		}
		GarageParkingCostCalculator.Result priced = GarageParkingCostCalculator.cents(tariff, stay.arrival_s(),
				pricedDeparture_s, destination.getType(), parkingFree, residentOfZone, residentOfDistrict,
				garageOptions.decayMeters(), garageOptions.commuteDecayMeters(), () -> garageOptionsOf(person, destination),
				counter.garageOptionCounters());
		return new ParkingPrice(new ParkingCostCalculator.Result(priced.cents(), priced.outcome()), zoneId,
				extendedByMinimumStay, priced.garageProbability(), priced.garagesInRange());
	}

	/**
	 * The garage options of the destination from the activity coordinate, which is in the network CRS (EPSG:25832, the CRS of
	 * the garage coordinates of the tariff model); no plan attribute is needed (spec Amendment E6). Looked up only for a stay
	 * that can reach a garage. An activity without a coordinate fails instead of being priced without garages: the garage
	 * options are on, so a silent skip would change the price of a stay.
	 */
	private GarageOptionModel.Option[] garageOptionsOf(Person person, Activity destination) {
		Coord coord = destination.getCoord();
		if (coord == null) {
			throw new IllegalStateException("person " + person.getId() + ": activity " + destination.getType()
					+ " has no coordinate, which the garage options of the tariff model " + tariffsPath
					+ " (garage_decay_m " + garageOptions.decayMeters() + " m, garage_decay_commute_m "
					+ garageOptions.commuteDecayMeters() + " m) need to find the garages within "
					+ garageOptions.maximumDistanceMeters() + " m; set garage_decay_m and garage_decay_commute_m to 0 to switch"
					+ " the garage options off");
		}
		return garageOptions.optionsWithin(coord.getX(), coord.getY());
	}

	/** Arrival and departure exactly as BraunschweigCarCostModel.calculateParkingCost_EUR determines them. */
	private Stay stay(Person person, DiscreteModeChoiceTrip trip, List<? extends PlanElement> elements,
			ZoneTariff tariff) {
		Activity destination = trip.getDestinationActivity();
		TimeTracker timeTracker = new TimeTracker(timeInterpretation);
		timeTracker.setTime(trip.getDepartureTime());
		// Element by element, not addElements: after an element without a defined end, TimeTracker fails on the next one
		// with a message that names neither the element nor the trip.
		int index = 0;
		for (PlanElement element : elements) {
			if (timeTracker.addElement(element).isUndefined()) {
				throw new IllegalStateException(String.format(Locale.ROOT,
						"person %s: element %d (%s) of the car trip at trip index %d (%s to %s, departing at %.1f s) has no"
								+ " defined end time, so the parking stay at the destination has no arrival; every element of"
								+ " a routed trip needs a travel time (leg) or an end time or maximum duration (activity)",
						person.getId(), index, describe(element), trip.getIndex(), trip.getOriginActivity().getType(),
						destination.getType(), trip.getDepartureTime()));
			}
			index++;
		}
		double arrival_s = timeTracker.getTime().seconds();
		if (isTerminal(person, destination)) {
			// Never before the arrival (ParkingCostCalculator.terminalDeparture_s), so no further check is needed.
			return new Stay(arrival_s, ParkingCostCalculator.terminalDeparture_s(arrival_s, tariff.feeEnd_s()));
		}
		OptionalTime departure = timeTracker.addActivity(destination);
		if (departure.isUndefined()) {
			throw new IllegalStateException("person " + person.getId() + ": activity " + destination.getType()
					+ " is not the last element of the selected plan but has neither an end time nor a maximum duration,"
					+ " so its parking stay has no end");
		}
		double departure_s = departure.seconds();
		if (departure_s < arrival_s) {
			throw new IllegalStateException(String.format(Locale.ROOT,
					"person %s: activity %s ends at %.1f s, before the car arrives at %.1f s; a parking stay needs departure"
							+ " >= arrival. The MATSim time interpretation ended the activity before the arrival: use"
							+ " plans.tripDurationHandling = shiftActivityEndTimes (set by eqasim's GenerateConfig) and an"
							+ " activityDurationInterpretation other than endTimeOnly",
					person.getId(), destination.getType(), departure_s, arrival_s));
		}
		return new Stay(arrival_s, departure_s);
	}

	/** The destination is the last element of the selected plan (identity, as in BraunschweigCarCostModel). */
	private static boolean isTerminal(Person person, Activity destination) {
		Plan plan = person.getSelectedPlan();
		if (plan == null) {
			throw new IllegalStateException("person " + person.getId()
					+ " has no selected plan; the terminal-stay rule needs it to find the last activity");
		}
		List<PlanElement> planElements = plan.getPlanElements();
		return destination == planElements.get(planElements.size() - 1);
	}

	/** A routed trip element for an error message: "leg" and its mode, or "activity" and its type. */
	private static String describe(PlanElement element) {
		if (element instanceof Leg leg) {
			return "leg " + leg.getMode();
		}
		if (element instanceof Activity activity) {
			return "activity " + activity.getType();
		}
		return element.getClass().getName();
	}

	/**
	 * Rule R2 (spec Amendment C3): whether the destination lies in the resident parking district of the person's home,
	 * i.e. the activity attribute parkingDistrict and the person attribute residentParkingDistrict are both set and equal.
	 * An activity in no district and a person without a home district are both false, the same input as a stay in another
	 * district; the calculator receives the flag only. A district id the tariff model does not list fails: the plans and
	 * the model must come from the same release, and an unknown id would silently free or charge by chance.
	 */
	private boolean residentOfDistrict(Person person, Activity destination) {
		String activityDistrict = ParkingAttributes.activityDistrict(tariffs, tariffsPath, person, destination);
		String homeDistrict = ParkingAttributes.homeDistrict(tariffs, tariffsPath, person);
		return activityDistrict != null && activityDistrict.equals(homeDistrict);
	}
}
