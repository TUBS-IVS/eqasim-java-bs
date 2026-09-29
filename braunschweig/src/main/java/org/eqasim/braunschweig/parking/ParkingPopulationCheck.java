package org.eqasim.braunschweig.parking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.braunschweig.parking.ZoneTariff.ZoneType;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.StageActivityHandling;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Startup check of the plans against the parking tariff model (design "parking cost zones"; eqasim-bs issue #436),
 * bound by ParkingModule as a controller listener, so it exists only when the braunschweigParking module is enabled.
 *
 * <p>The constructor takes the {@link ParkingTariffs}, so the tariff model loads when the controller builds its
 * listeners, before the startup event and before any replanning prices a car trip, independent of other bindings that
 * may need it earlier: a broken tariff file stops the run at the start. At the startup event the check scans every plan
 * of every person once (the prepared population has one plan per person, a population of output plans several) and
 * fails with an IllegalStateException, before iteration 0, when
 * <ul>
 * <li>an activity attribute {@value ZoneParkingCarCostModel#ZONE_ATTRIBUTE} names no zone of the tariff model;</li>
 * <li>the person attribute {@value ZoneParkingCarCostModel#RESIDENT_ATTRIBUTE} names no zone of the tariff model, or a
 * zone of another type than resident_zone (only a resident zone exempts its residents);</li>
 * <li>an activity carries {@value ZoneParkingCarCostModel#FREE_ATTRIBUTE} = true but no zone: the eqasim-bs plans
 * writer never writes that, so it shows a writer defect;</li>
 * <li>a person or an activity carries {@code isParis}, of any value: the plans were written for the legacy 8 km ring
 * parking (Python flag enable_urban_parking), which is mutually exclusive with the zones (Python flag
 * parking_zones_enabled);</li>
 * <li>one of the three parking attributes has another type than the plans writer writes (String zone ids, Boolean
 * parkingFree), which ZoneParkingCarCostModel would otherwise reject only when it first prices a car trip there.</li>
 * </ul>
 * The message names the person, the plan and activity index and the activity type where applicable, the attribute and
 * the offending value, and, except for a mistyped value, the tariffs path. Stage activities (such as
 * {@code car interaction}), which the router inserts without attributes and at which no car stay is priced, are
 * skipped; the activity index counts the other activities of a plan from 0, like the activity_index of the synthesis
 * frames.
 *
 * <p>Fallback transparency: one {@code [parking] population: ...} line reports the coverage as counts and rates
 * ({@link #summary}). It is a warning when activities exist but none lies in a zone: every car stay is then priced
 * NO_ZONE, the signature of plans written without the zones.
 *
 * <p>Side effects: the log line; the population is only read.
 */
@Singleton
public final class ParkingPopulationCheck implements StartupListener {
	private static final Logger LOG = LogManager.getLogger(ParkingPopulationCheck.class);

	/**
	 * Person and activity attribute (Boolean) of the legacy 8 km ring parking around Braunschweig Hbf: written by the
	 * eqasim-bs plans writer under the Python flag enable_urban_parking, read by BraunschweigCarCostModel and the car,
	 * car passenger and bicycle utility estimators of this module.
	 */
	static final String LEGACY_RING_ATTRIBUTE = "isParis";

	private final ParkingTariffs tariffs;
	private final Population population;
	private final String tariffsPath;

	/**
	 * Coverage of the parking attributes, counted over every plan of every person without stage activities.
	 *
	 * @param persons persons in the population
	 * @param activities activities of all plans
	 * @param activitiesInZones activities with a parkingZone by the type of that zone
	 * @param parkingFreeActivities activities with parkingFree = true
	 * @param residentPersons persons with a residentParkingZone
	 */
	record Coverage(long persons, long activities, Map<ZoneType, Long> activitiesInZones, long parkingFreeActivities,
			long residentPersons) {
		Coverage {
			Map<ZoneType, Long> copy = new EnumMap<>(ZoneType.class);
			copy.putAll(activitiesInZones);
			activitiesInZones = Collections.unmodifiableMap(copy);
		}

		/** Activities with a parkingZone of any type. */
		long activitiesInAZone() {
			return activitiesInZones.values().stream().mapToLong(Long::longValue).sum();
		}

		/** Activities exist, but none lies in a zone: every car stay would be priced NO_ZONE. */
		boolean noActivityInAZone() {
			return activities > 0 && activitiesInAZone() == 0;
		}
	}

	@Inject
	public ParkingPopulationCheck(ParkingTariffs tariffs, Population population, ParkingConfigGroup parking) {
		this.tariffs = Objects.requireNonNull(tariffs, "tariffs");
		this.population = Objects.requireNonNull(population, "population");
		this.tariffsPath = parking.getTariffsPath();
	}

	/** Scans the population and logs its coverage; fails on the first inconsistency, see the class comment. */
	@Override
	public void notifyStartup(StartupEvent event) {
		Coverage coverage = scan();
		if (coverage.noActivityInAZone()) {
			LOG.warn(summary(coverage));
		} else {
			LOG.info(summary(coverage));
		}
	}

	/** Checks every plan of every person and counts the coverage, without logging. */
	Coverage scan() {
		Map<ZoneType, Long> activitiesInZones = new EnumMap<>(ZoneType.class);
		for (ZoneType zoneType : ZoneType.values()) {
			activitiesInZones.put(zoneType, 0L);
		}
		long activities = 0;
		long parkingFreeActivities = 0;
		long residentPersons = 0;
		for (Person person : population.getPersons().values()) {
			Object personRing = person.getAttributes().getAttribute(LEGACY_RING_ATTRIBUTE);
			if (personRing != null) {
				throw legacyRing(personName(person), personRing);
			}
			if (residentZone(person) != null) {
				residentPersons++;
			}
			List<? extends Plan> plans = person.getPlans();
			for (int planIndex = 0; planIndex < plans.size(); planIndex++) {
				List<Activity> planActivities = TripStructureUtils.getActivities(plans.get(planIndex),
						StageActivityHandling.ExcludeStageActivities);
				for (int activityIndex = 0; activityIndex < planActivities.size(); activityIndex++) {
					Activity activity = planActivities.get(activityIndex);
					ZoneType zoneType = zoneType(person, planIndex, activityIndex, activity);
					if (zoneType != null) {
						activitiesInZones.merge(zoneType, 1L, Long::sum);
					}
					if (parkingFree(person, planIndex, activityIndex, activity, zoneType != null)) {
						parkingFreeActivities++;
					}
					activities++;
				}
			}
		}
		return new Coverage(population.getPersons().size(), activities, activitiesInZones, parkingFreeActivities,
				residentPersons);
	}

	/**
	 * The {@code [parking] population} line: persons and activities; activities in a zone with their rate among all
	 * activities and their count per zone type, in declaration order; parkingFree activities; persons with a resident
	 * zone and their rate among all persons. A rate of an empty total is 0.
	 */
	static String summary(Coverage coverage) {
		List<String> byZoneType = new ArrayList<>();
		for (ZoneType zoneType : ZoneType.values()) {
			byZoneType.add(zoneType.jsonName() + " " + coverage.activitiesInZones().getOrDefault(zoneType, 0L));
		}
		String summary = String.format(Locale.ROOT,
				"[parking] population: %d persons, %d activities; %d in a zone (%.1f %%: %s), %d parkingFree, %d persons"
						+ " with residentParkingZone (%.1f %%)",
				coverage.persons(), coverage.activities(), coverage.activitiesInAZone(),
				percent(coverage.activitiesInAZone(), coverage.activities()), String.join(", ", byZoneType),
				coverage.parkingFreeActivities(), coverage.residentPersons(),
				percent(coverage.residentPersons(), coverage.persons()));
		if (coverage.noActivityInAZone()) {
			summary += "; no activity lies in a parking zone although " + ParkingConfigGroup.GROUP_NAME
					+ " is enabled, so every car stay is priced NO_ZONE: were the plans written with parking_zones_enabled?";
		}
		return summary;
	}

	/**
	 * The type of the zone the activity lies in, or null outside every zone; fails for the legacy ring attribute and for
	 * a mistyped or unknown parkingZone.
	 */
	private ZoneType zoneType(Person person, int planIndex, int activityIndex, Activity activity) {
		Object ring = activity.getAttributes().getAttribute(LEGACY_RING_ATTRIBUTE);
		if (ring != null) {
			throw legacyRing(activityName(person, planIndex, activityIndex, activity), ring);
		}
		Object value = activity.getAttributes().getAttribute(ZoneParkingCarCostModel.ZONE_ATTRIBUTE);
		if (value == null) {
			return null;
		}
		if (!(value instanceof String zoneId)) {
			throw mistyped(activityName(person, planIndex, activityIndex, activity), ZoneParkingCarCostModel.ZONE_ATTRIBUTE,
					String.class, value);
		}
		Optional<ZoneTariff> tariff = tariffs.zone(zoneId);
		if (tariff.isEmpty()) {
			throw unknownZone(activityName(person, planIndex, activityIndex, activity),
					ZoneParkingCarCostModel.ZONE_ATTRIBUTE, zoneId);
		}
		return tariff.get().zoneType();
	}

	/**
	 * Whether the activity carries parkingFree = true; fails for a mistyped value and for free parking outside every
	 * zone.
	 */
	private boolean parkingFree(Person person, int planIndex, int activityIndex, Activity activity, boolean inZone) {
		Object value = activity.getAttributes().getAttribute(ZoneParkingCarCostModel.FREE_ATTRIBUTE);
		if (value == null) {
			return false;
		}
		if (!(value instanceof Boolean free)) {
			throw mistyped(activityName(person, planIndex, activityIndex, activity), ZoneParkingCarCostModel.FREE_ATTRIBUTE,
					Boolean.class, value);
		}
		if (free && !inZone) {
			throw new IllegalStateException(activityName(person, planIndex, activityIndex, activity) + ": "
					+ ZoneParkingCarCostModel.FREE_ATTRIBUTE + " true without a " + ZoneParkingCarCostModel.ZONE_ATTRIBUTE
					+ "; the eqasim-bs plans writer never writes free parking outside the zones of the parking tariff model "
					+ tariffsPath + " (" + ParkingConfigGroup.GROUP_NAME + ".tariffsPath), so the plans come from a"
					+ " defective writer");
		}
		return free;
	}

	/**
	 * The person's residentParkingZone, or null for a person living in no resident zone; fails for a mistyped value, an
	 * unknown zone and a zone of another type than resident_zone.
	 */
	private String residentZone(Person person) {
		Object value = person.getAttributes().getAttribute(ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE);
		if (value == null) {
			return null;
		}
		if (!(value instanceof String zoneId)) {
			throw mistyped(personName(person), ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, String.class, value);
		}
		Optional<ZoneTariff> tariff = tariffs.zone(zoneId);
		if (tariff.isEmpty()) {
			throw unknownZone(personName(person), ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE, zoneId);
		}
		ZoneType zoneType = tariff.get().zoneType();
		if (zoneType != ZoneType.RESIDENT_ZONE) {
			throw new IllegalStateException(personName(person) + ": " + ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE + " "
					+ zoneId + " is a " + zoneType.jsonName() + " zone of the parking tariff model " + tariffsPath + " ("
					+ ParkingConfigGroup.GROUP_NAME + ".tariffsPath), not a " + ZoneType.RESIDENT_ZONE.jsonName()
					+ "; only a resident zone exempts its residents");
		}
		return zoneId;
	}

	private IllegalStateException unknownZone(String owner, String attribute, String zoneId) {
		return new IllegalStateException(owner + ": " + attribute + " " + zoneId + " is not a zone of the parking tariff"
				+ " model " + tariffsPath + " (" + ParkingConfigGroup.GROUP_NAME + ".tariffsPath); the tariff model and the"
				+ " plans must come from the same release");
	}

	private IllegalStateException legacyRing(String owner, Object value) {
		return new IllegalStateException(owner + ": " + LEGACY_RING_ATTRIBUTE + " " + value + " belongs to the legacy 8 km"
				+ " ring parking (Python flag enable_urban_parking), which is mutually exclusive with the zone-based parking"
				+ " costs of this run (" + ParkingConfigGroup.GROUP_NAME + ", Python flag parking_zones_enabled, parking"
				+ " tariff model " + tariffsPath + "); write the plans with enable_urban_parking = false");
	}

	private static IllegalStateException mistyped(String owner, String attribute, Class<?> expected, Object value) {
		return new IllegalStateException(owner + ": " + attribute + " must be a " + expected.getName() + ", got "
				+ value.getClass().getName() + " " + value + "; the eqasim-bs plans writer writes it with this type");
	}

	/** Names are built only for an error message: the scan visits every activity of the population. */
	private static String personName(Person person) {
		return "person " + person.getId();
	}

	private static String activityName(Person person, int planIndex, int activityIndex, Activity activity) {
		return personName(person) + ", plan " + planIndex + ", activity " + activityIndex + " (" + activity.getType() + ")";
	}

	private static double percent(long count, long total) {
		return total == 0 ? 0.0 : 100.0 * count / total;
	}
}
