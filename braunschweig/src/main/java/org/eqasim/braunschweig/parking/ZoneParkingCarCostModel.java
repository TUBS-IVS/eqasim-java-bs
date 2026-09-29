package org.eqasim.braunschweig.parking;

import java.util.List;
import java.util.Locale;

import org.eqasim.braunschweig.mode_choice.parameters.BraunschweigCostParameters;
import org.eqasim.core.simulation.mode_choice.cost.AbstractCostModel;
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
 * <p>Every call records exactly one outcome in the {@link ParkingOutcomeCounter}, NO_ZONE included, so the
 * per-iteration report shows which rule priced the car trips; a failing call records nothing. An unknown zone id (in
 * either zone attribute), a mistyped attribute, a non-terminal activity without an end and an activity ending before
 * the arrival raise IllegalStateException naming the person, the activity and the attribute; a routed trip element
 * without a defined end raises it naming the person, the index and type of the element and the car trip.
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

	private static final ParkingCostCalculator.Result NO_ZONE = new ParkingCostCalculator.Result(0, ParkingOutcome.NO_ZONE);
	private static final double CENTS_PER_EURO = 100.0;

	private final BraunschweigCostParameters costParameters;
	private final TimeInterpretation timeInterpretation;
	private final ParkingTariffs tariffs;
	private final ParkingOutcomeCounter counter;
	private final String tariffsPath;

	/** The car stay at the destination in simulation seconds: [arrival_s, departure_s). */
	private record Stay(double arrival_s, double departure_s) {
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
	}

	/** Driving cost plus the parking cost at the destination, in euros; records the parking outcome. */
	@Override
	public double calculateCost_MU(Person person, DiscreteModeChoiceTrip trip, List<? extends PlanElement> elements) {
		double drivingCost_EUR = costParameters.carCost_EUR_km * getInVehicleDistance_km(elements);
		ParkingCostCalculator.Result parking = parkingCost(person, trip, elements);
		counter.record(parking.outcome());
		return drivingCost_EUR + parking.cents() / CENTS_PER_EURO;
	}

	private ParkingCostCalculator.Result parkingCost(Person person, DiscreteModeChoiceTrip trip,
			List<? extends PlanElement> elements) {
		Activity destination = trip.getDestinationActivity();
		String zoneId = zoneId(person, destination);
		if (zoneId == null) {
			return NO_ZONE;
		}
		ZoneTariff tariff = knownZone(person, ZONE_ATTRIBUTE, zoneId);
		boolean parkingFree = parkingFree(person, destination);
		boolean residentOfZone = zoneId.equals(residentZoneId(person));
		Stay stay = stay(person, trip, elements, tariff);
		return ParkingCostCalculator.cents(tariff, stay.arrival_s(), stay.departure_s(), destination.getType(), parkingFree,
				residentOfZone);
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

	/** The zone id of the destination, or null outside every zone (assumption Z1). */
	private static String zoneId(Person person, Activity destination) {
		Object value = destination.getAttributes().getAttribute(ZONE_ATTRIBUTE);
		if (value == null) {
			return null;
		}
		if (value instanceof String zoneId) {
			return zoneId;
		}
		throw mistyped(person, "activity " + destination.getType(), ZONE_ATTRIBUTE, String.class, value);
	}

	private static boolean parkingFree(Person person, Activity destination) {
		Object value = destination.getAttributes().getAttribute(FREE_ATTRIBUTE);
		if (value == null) {
			return false;
		}
		if (value instanceof Boolean free) {
			return free;
		}
		throw mistyped(person, "activity " + destination.getType(), FREE_ATTRIBUTE, Boolean.class, value);
	}

	/** The resident zone of the person, or null for a person living in no resident zone. */
	private String residentZoneId(Person person) {
		Object value = person.getAttributes().getAttribute(RESIDENT_ATTRIBUTE);
		if (value == null) {
			return null;
		}
		if (!(value instanceof String zoneId)) {
			throw mistyped(person, "person", RESIDENT_ATTRIBUTE, String.class, value);
		}
		// The resident zone comes from the same release as the tariff model: an unknown id would silently exempt nobody.
		knownZone(person, RESIDENT_ATTRIBUTE, zoneId);
		return zoneId;
	}

	private ZoneTariff knownZone(Person person, String attribute, String zoneId) {
		return tariffs.zone(zoneId).orElseThrow(() -> new IllegalStateException("person " + person.getId() + ": "
				+ attribute + " " + zoneId + " is not a zone of the parking tariff model " + tariffsPath + " ("
				+ ParkingConfigGroup.GROUP_NAME + ".tariffsPath); the tariff model and the plans must come from the same"
				+ " release"));
	}

	private static IllegalStateException mistyped(Person person, String owner, String attribute, Class<?> expected,
			Object value) {
		return new IllegalStateException("person " + person.getId() + ": " + owner + " attribute " + attribute
				+ " must be a " + expected.getName() + ", got " + value.getClass().getName() + " " + value
				+ "; the eqasim-bs plans writer writes it with this type");
	}
}
