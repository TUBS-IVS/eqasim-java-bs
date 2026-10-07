package org.eqasim.braunschweig.parking;

import java.util.List;
import java.util.Optional;

import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.StageActivityHandling;

/**
 * Reads the parking attributes of the plans that the zone parking car cost model, the parking search time and the startup
 * check of the plans all need (eqasim-bs issue #436): the zone an activity lies in (activity attribute parkingZone), its
 * free-parking flag (parkingFree), the resident parking districts (parkingDistrict of an activity, residentParkingDistrict of
 * a person) and the resident zone of a person (residentParkingZone). One parsing and one message format serve every reader,
 * so that the accepted and the rejected inputs of the readers cannot drift apart: a value that has another type than the
 * plans writer writes, or that names a zone or a district the tariff model does not list, fails with an IllegalStateException
 * that names the owner (the person, the plan and the activity, or the person alone for a person attribute), the attribute,
 * the offending value and, for an unknown id, the tariffs path.
 *
 * <p>Names are built only for an error message, never on the way to a valid value: the readers run for every priced car
 * stay and for every activity of the population. A reader that knows the position of the activity in the plans of the
 * person (the startup check of the plans) passes it; every other reader passes {@link #UNKNOWN_POSITION}, and the position
 * is then looked up in the plans of the person, which only an error message costs.
 *
 * <p>Stateless; every method is a pure function of its arguments and reads the person and the activity only.
 */
final class ParkingAttributes {
	/** Position of an activity within the plans of its person that the caller does not know: the plans are searched. */
	static final int UNKNOWN_POSITION = -1;

	/** The kind of id named in the message when the parkingZone or the residentParkingZone attribute is unknown. */
	static final String ZONE_KIND = "a zone";

	/** The kind of id named in the message when a district attribute is unknown. */
	static final String DISTRICT_KIND = "a resident district";

	private ParkingAttributes() {
	}

	/**
	 * The tariff of the zone the activity lies in (activity attribute parkingZone), or null for an activity outside every zone
	 * (assumption Z1); the position of the activity in the plans of the person is looked up for an error message.
	 *
	 * @throws IllegalStateException for a value that is not a String or names a zone the tariff model does not contain: the
	 *         plans and the model must come from the same release
	 */
	static ZoneTariff zone(ParkingTariffs tariffs, String tariffsPath, Person person, Activity activity) {
		return zone(tariffs, tariffsPath, person, UNKNOWN_POSITION, UNKNOWN_POSITION, activity);
	}

	/** {@link #zone(ParkingTariffs, String, Person, Activity)} for an activity whose position in the plans is known. */
	static ZoneTariff zone(ParkingTariffs tariffs, String tariffsPath, Person person, int planIndex, int activityIndex,
			Activity activity) {
		Object value = activity.getAttributes().getAttribute(ZoneParkingCarCostModel.ZONE_ATTRIBUTE);
		if (value == null) {
			return null;
		}
		if (!(value instanceof String zoneId)) {
			throw mistyped(describe(person, planIndex, activityIndex, activity), ZoneParkingCarCostModel.ZONE_ATTRIBUTE,
					String.class, value);
		}
		Optional<ZoneTariff> tariff = tariffs.zone(zoneId);
		if (tariff.isEmpty()) {
			throw unknown(describe(person, planIndex, activityIndex, activity), ZoneParkingCarCostModel.ZONE_ATTRIBUTE, zoneId,
					ZONE_KIND, tariffsPath);
		}
		return tariff.get();
	}

	/**
	 * Whether the activity carries parkingFree = true (activity attribute parkingFree); absent means false. The position of
	 * the activity in the plans of the person is looked up for an error message.
	 *
	 * @throws IllegalStateException for a value that is not a Boolean
	 */
	static boolean parkingFree(Person person, Activity activity) {
		return parkingFree(person, UNKNOWN_POSITION, UNKNOWN_POSITION, activity);
	}

	/** {@link #parkingFree(Person, Activity)} for an activity whose position in the plans of the person is known. */
	static boolean parkingFree(Person person, int planIndex, int activityIndex, Activity activity) {
		Object value = activity.getAttributes().getAttribute(ZoneParkingCarCostModel.FREE_ATTRIBUTE);
		if (value == null) {
			return false;
		}
		if (value instanceof Boolean free) {
			return free;
		}
		throw mistyped(describe(person, planIndex, activityIndex, activity), ZoneParkingCarCostModel.FREE_ATTRIBUTE,
				Boolean.class, value);
	}

	/**
	 * The tariff of the resident zone containing the person's home (person attribute residentParkingZone), or null for a
	 * person living in no resident zone. The zone type is not checked here: the startup check of the plans requires a
	 * resident_zone, the cost model compares the id with the zone of the destination only.
	 *
	 * @throws IllegalStateException for a value that is not a String or names a zone the tariff model does not contain: an
	 *         unknown id would silently exempt nobody
	 */
	static ZoneTariff residentZone(ParkingTariffs tariffs, String tariffsPath, Person person) {
		Object value = person.getAttributes().getAttribute(ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE);
		if (value == null) {
			return null;
		}
		if (!(value instanceof String zoneId)) {
			throw mistyped(describe(person), ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, String.class, value);
		}
		Optional<ZoneTariff> tariff = tariffs.zone(zoneId);
		if (tariff.isEmpty()) {
			throw unknown(describe(person), ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, zoneId, ZONE_KIND, tariffsPath);
		}
		return tariff.get();
	}

	/**
	 * The id of the resident parking district the activity lies in (activity attribute parkingDistrict, rule R2), or null for
	 * an activity in no district; the position of the activity in the plans of the person is looked up for an error message.
	 *
	 * @throws IllegalStateException for a value that is not a String or a district the tariff model does not list
	 */
	static String activityDistrict(ParkingTariffs tariffs, String tariffsPath, Person person, Activity activity) {
		return activityDistrict(tariffs, tariffsPath, person, UNKNOWN_POSITION, UNKNOWN_POSITION, activity);
	}

	/** {@link #activityDistrict(ParkingTariffs, String, Person, Activity)} for an activity whose position is known. */
	static String activityDistrict(ParkingTariffs tariffs, String tariffsPath, Person person, int planIndex, int activityIndex,
			Activity activity) {
		Object value = activity.getAttributes().getAttribute(ZoneParkingCarCostModel.DISTRICT_ATTRIBUTE);
		if (value == null) {
			return null;
		}
		if (!(value instanceof String districtId)) {
			throw mistyped(describe(person, planIndex, activityIndex, activity), ZoneParkingCarCostModel.DISTRICT_ATTRIBUTE,
					String.class, value);
		}
		if (tariffs.residentDistrict(districtId).isEmpty()) {
			throw unknown(describe(person, planIndex, activityIndex, activity), ZoneParkingCarCostModel.DISTRICT_ATTRIBUTE,
					districtId, DISTRICT_KIND, tariffsPath);
		}
		return districtId;
	}

	/**
	 * The id of the resident parking district of the person's home (person attribute residentParkingDistrict, rule R2), or
	 * null for a person whose home lies in no district.
	 *
	 * @throws IllegalStateException for a value that is not a String or a district the tariff model does not list
	 */
	static String homeDistrict(ParkingTariffs tariffs, String tariffsPath, Person person) {
		Object value = person.getAttributes().getAttribute(ZoneParkingCarCostModel.RESIDENT_DISTRICT_ATTRIBUTE);
		if (value == null) {
			return null;
		}
		if (!(value instanceof String districtId)) {
			throw mistyped(describe(person), ZoneParkingCarCostModel.RESIDENT_DISTRICT_ATTRIBUTE, String.class, value);
		}
		if (tariffs.residentDistrict(districtId).isEmpty()) {
			throw unknown(describe(person), ZoneParkingCarCostModel.RESIDENT_DISTRICT_ATTRIBUTE, districtId, DISTRICT_KIND,
					tariffsPath);
		}
		return districtId;
	}

	/** The owner of a person attribute in a message: {@code person p1}. */
	static String describe(Person person) {
		return "person " + person.getId();
	}

	/**
	 * The owner of an activity attribute in a message: {@code person p1, plan 0, activity 1 (work)}, the activity index
	 * counting the main activities of a plan from 0 like the activity_index of the synthesis frames. For
	 * {@link #UNKNOWN_POSITION} the plans of the person are searched for the activity (by identity); an activity in none of
	 * them, such as the synthetic trip of a test, is named {@code person p1, activity (work) outside the plans of the person}.
	 */
	static String describe(Person person, int planIndex, int activityIndex, Activity activity) {
		if (planIndex != UNKNOWN_POSITION) {
			return describe(person) + ", plan " + planIndex + ", activity " + activityIndex + " (" + activity.getType() + ")";
		}
		List<? extends Plan> plans = person.getPlans();
		for (int plan = 0; plan < plans.size(); plan++) {
			List<Activity> activities = TripStructureUtils.getActivities(plans.get(plan),
					StageActivityHandling.ExcludeStageActivities);
			for (int index = 0; index < activities.size(); index++) {
				if (activities.get(index) == activity) {
					return describe(person, plan, index, activity);
				}
			}
		}
		return describe(person) + ", activity (" + activity.getType() + ") outside the plans of the person";
	}

	/** An attribute of another type than the plans writer writes. */
	static IllegalStateException mistyped(String owner, String attribute, Class<?> expected, Object value) {
		return new IllegalStateException(owner + ": " + attribute + " must be a " + expected.getName() + ", got "
				+ value.getClass().getName() + " " + value + "; the eqasim-bs plans writer writes it with this type");
	}

	/** An id that the tariff model does not list; {@code kind} says what it should have been, e.g. {@link #ZONE_KIND}. */
	static IllegalStateException unknown(String owner, String attribute, String id, String kind, String tariffsPath) {
		return new IllegalStateException(owner + ": " + attribute + " " + id + " is not " + kind + " of the parking tariff"
				+ " model " + tariffsPath + " (" + ParkingConfigGroup.GROUP_NAME + ".tariffsPath); the tariff model and the"
				+ " plans must come from the same release");
	}
}
