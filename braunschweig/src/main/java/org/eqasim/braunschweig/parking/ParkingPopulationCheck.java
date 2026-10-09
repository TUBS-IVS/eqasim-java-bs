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
 * <li>an activity attribute {@value ZoneParkingCarCostModel#DISTRICT_ATTRIBUTE} or the person attribute
 * {@value ZoneParkingCarCostModel#RESIDENT_DISTRICT_ATTRIBUTE} (rule R2, spec Amendment C3) names no resident district of
 * the tariff model's {@code resident_districts}: a model of schema 1 lists none, so any district value fails against
 * it, and the plans and the model must come from the same release. A district outside every fee zone is legitimate (the
 * districts are independent of the fee zones; Z1 decides such a stay);</li>
 * <li>an activity carries {@value ZoneParkingCarCostModel#FREE_ATTRIBUTE} = true but no zone: the eqasim-bs plans
 * writer never writes that, so it shows a writer defect;</li>
 * <li>a person or an activity carries {@code isParis}, of any value: the plans were written for the legacy 8 km ring
 * parking (Python flag enable_urban_parking), which is mutually exclusive with the zones (Python flag
 * parking_zones_enabled);</li>
 * <li>the garage options are on (schema 3 or 4 model, {@code garage_decay_m} or {@code garage_decay_commute_m} above 0) and
 * an activity with a
 * {@value ZoneParkingCarCostModel#ZONE_ATTRIBUTE} has no coordinate: the garages within the maximum distance are found from
 * the activity coordinate (spec Amendment E6), so the run stops here, at the start, instead of at the first car stay that
 * reaches the garage options; the message names the way out, both decays 0;</li>
 * <li>one of the five parking attributes has another type than the plans writer writes (String zone and district ids,
 * Boolean parkingFree), which ZoneParkingCarCostModel would otherwise reject only when it first prices a car trip
 * there.</li>
 * </ul>
 * The message names the person, the plan and activity index and the activity type where applicable, the attribute and
 * the offending value, and, except for a mistyped value, the tariffs path, in the one format of {@link ParkingAttributes},
 * which reads the five attributes for this check, the car cost model and the parking search time. Stage activities (such as
 * {@code car interaction}), which the router inserts without attributes and at which no car stay is priced, are
 * skipped; the activity index counts the other activities of a plan from 0, like the activity_index of the synthesis
 * frames.
 *
 * <p>Fallback transparency: one {@code [parking] population: ...} line reports the coverage as counts and rates
 * ({@link #summary}). It is a warning when activities exist but none lies in a zone: every car stay is then priced
 * NO_ZONE, the signature of plans written without the zones. A second warning ({@link #searchTimeWarning}) says that the
 * search time factor of lever 4 cannot act in this run, when it is configured and the tariff model carries search times
 * but no activity of the population lies in such a zone; a factor above 0 with no search time in the model at all fails
 * the run in {@link ParkingSearchTimeCheck}.
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
	private final double searchTimeFactor;
	private final String searchTimeFactorText;

	/**
	 * Coverage of the parking attributes, counted over every plan of every person without stage activities.
	 *
	 * @param persons persons in the population
	 * @param activities activities of all plans
	 * @param activitiesInZones activities with a parkingZone by the type of that zone
	 * @param parkingFreeActivities activities with parkingFree = true
	 * @param residentPersons persons with a residentParkingZone
	 * @param activitiesInDistricts activities with a parkingDistrict
	 * @param districtResidents persons with a residentParkingDistrict
	 * @param ownDistrictActivitiesInPermitZones activities in the district of the person's home (both attributes set and
	 *        equal) that lie in a zone which honours resident permits: the stays rule R2 frees unless the activity is home
	 *        or parkingFree
	 * @param activitiesWithSearchTime activities the search time of lever 4 acts on: in a zone whose search_time_min is above
	 *        0 and not of purpose home, counted by the same predicate as the car utility term
	 *        ({@link ParkingSearchTime#minutesIn})
	 */
	record Coverage(long persons, long activities, Map<ZoneType, Long> activitiesInZones, long parkingFreeActivities,
			long residentPersons, long activitiesInDistricts, long districtResidents,
			long ownDistrictActivitiesInPermitZones, long activitiesWithSearchTime) {
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
		this.searchTimeFactor = parking.searchTimeFactor();
		this.searchTimeFactorText = parking.getSearchTimeFactor();
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
		searchTimeWarning(coverage).ifPresent(LOG::warn);
	}

	/**
	 * The warning that the search time factor cannot act in this run (lever 4): the factor is above 0 and the tariff model
	 * carries a search time in at least one zone, but no activity of the population lies in such a zone, so every car
	 * utility would equal the one without the term. Empty with the default factor 0 (off), when an activity lies in a zone
	 * with a search time, and when the tariff model has no search time at all (ParkingSearchTimeCheck fails that run).
	 */
	Optional<String> searchTimeWarning(Coverage coverage) {
		long zones = tariffs.zonesWithSearchTime();
		if (searchTimeFactor == 0.0 || zones == 0 || coverage.activitiesWithSearchTime() > 0) {
			return Optional.empty();
		}
		return Optional.of("[parking] searchTimeFactor " + searchTimeFactorText + " is configured and "
				+ (zones == 1 ? "1 zone carries" : zones + " zones carry") + " a search_time_min above 0, but no activity of the"
				+ " population lies in such a zone (home destinations excluded): the search time cannot act in this run; were"
				+ " the plans written with the parkingZone attributes of these zones?");
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
		long activitiesInDistricts = 0;
		long districtResidents = 0;
		long ownDistrictActivitiesInPermitZones = 0;
		long activitiesWithSearchTime = 0;
		for (Person person : population.getPersons().values()) {
			Object personRing = person.getAttributes().getAttribute(LEGACY_RING_ATTRIBUTE);
			if (personRing != null) {
				throw legacyRing(ParkingAttributes.describe(person), personRing);
			}
			if (residentZone(person) != null) {
				residentPersons++;
			}
			String homeDistrict = ParkingAttributes.homeDistrict(tariffs, tariffsPath, person);
			if (homeDistrict != null) {
				districtResidents++;
			}
			List<? extends Plan> plans = person.getPlans();
			for (int planIndex = 0; planIndex < plans.size(); planIndex++) {
				List<Activity> planActivities = TripStructureUtils.getActivities(plans.get(planIndex),
						StageActivityHandling.ExcludeStageActivities);
				for (int activityIndex = 0; activityIndex < planActivities.size(); activityIndex++) {
					Activity activity = planActivities.get(activityIndex);
					ZoneTariff zone = zone(person, planIndex, activityIndex, activity);
					if (zone != null) {
						requireCoordinateForGarageOptions(person, planIndex, activityIndex, activity);
						activitiesInZones.merge(zone.zoneType(), 1L, Long::sum);
						if (ParkingSearchTime.minutesIn(zone, activity.getType()) > 0) {
							activitiesWithSearchTime++;
						}
					}
					if (parkingFree(person, planIndex, activityIndex, activity, zone != null)) {
						parkingFreeActivities++;
					}
					String activityDistrict = ParkingAttributes.activityDistrict(tariffs, tariffsPath, person, planIndex,
								activityIndex, activity);
					if (activityDistrict != null) {
						activitiesInDistricts++;
						if (activityDistrict.equals(homeDistrict) && zone != null && zone.residentPermitsValid()) {
							ownDistrictActivitiesInPermitZones++;
						}
					}
					activities++;
				}
			}
		}
		return new Coverage(population.getPersons().size(), activities, activitiesInZones, parkingFreeActivities,
				residentPersons, activitiesInDistricts, districtResidents, ownDistrictActivitiesInPermitZones,
				activitiesWithSearchTime);
	}

	/**
	 * The {@code [parking] population} line: persons and activities; activities in a zone with their rate among all
	 * activities and their count per zone type, in declaration order; parkingFree activities; persons with a resident
	 * zone and their rate among all persons; activities in a resident district and their rate, persons with a resident
	 * district and their rate, and the activities in the own district that lie in a zone honouring resident permits (the
	 * stays rule R2 can free) with their rate among all activities; the activities lever 4 acts on (in a zone with a search
	 * time above 0 minutes, home excluded) with their rate. A rate of an empty total is 0.
	 */
	static String summary(Coverage coverage) {
		List<String> byZoneType = new ArrayList<>();
		for (ZoneType zoneType : ZoneType.values()) {
			byZoneType.add(zoneType.jsonName() + " " + coverage.activitiesInZones().getOrDefault(zoneType, 0L));
		}
		String summary = String.format(Locale.ROOT,
				"[parking] population: %d persons, %d activities; %d in a zone (%.1f %%: %s), %d parkingFree, %d persons"
						+ " with residentParkingZone (%.1f %%); %d in a resident district (%.1f %%), %d persons with"
						+ " residentParkingDistrict (%.1f %%), %d in the own district and a zone that honours resident"
						+ " permits (%.1f %%); %d in a zone with a search time (%.1f %%)",
				coverage.persons(), coverage.activities(), coverage.activitiesInAZone(),
				percent(coverage.activitiesInAZone(), coverage.activities()), String.join(", ", byZoneType),
				coverage.parkingFreeActivities(), coverage.residentPersons(),
				percent(coverage.residentPersons(), coverage.persons()), coverage.activitiesInDistricts(),
				percent(coverage.activitiesInDistricts(), coverage.activities()), coverage.districtResidents(),
				percent(coverage.districtResidents(), coverage.persons()), coverage.ownDistrictActivitiesInPermitZones(),
				percent(coverage.ownDistrictActivitiesInPermitZones(), coverage.activities()),
				coverage.activitiesWithSearchTime(), percent(coverage.activitiesWithSearchTime(), coverage.activities()));
		if (coverage.noActivityInAZone()) {
			summary += "; no activity lies in a parking zone although " + ParkingConfigGroup.GROUP_NAME
					+ " is enabled, so every car stay is priced NO_ZONE: were the plans written with parking_zones_enabled?";
		}
		return summary;
	}

	/**
	 * The tariff of the zone the activity lies in, or null outside every zone; fails for the legacy ring attribute and for
	 * a mistyped or unknown parkingZone.
	 */
	private ZoneTariff zone(Person person, int planIndex, int activityIndex, Activity activity) {
		Object ring = activity.getAttributes().getAttribute(LEGACY_RING_ATTRIBUTE);
		if (ring != null) {
			throw legacyRing(ParkingAttributes.describe(person, planIndex, activityIndex, activity), ring);
		}
		return ParkingAttributes.zone(tariffs, tariffsPath, person, planIndex, activityIndex, activity);
	}

	/**
	 * With the garage options on, an activity in a zone needs a coordinate (ZoneParkingCarCostModel finds its garages from
	 * it). Fails at the start, with the person, the plan and the activity, instead of mid-run at the first stay there.
	 */
	private void requireCoordinateForGarageOptions(Person person, int planIndex, int activityIndex, Activity activity) {
		if (tariffs.garageOptions().isActive() && activity.getCoord() == null) {
			throw new IllegalStateException(ParkingAttributes.describe(person, planIndex, activityIndex, activity)
					+ ": the activity lies in the parking zone " + activity.getAttributes()
							.getAttribute(ZoneParkingCarCostModel.ZONE_ATTRIBUTE)
					+ " but has no coordinate, which the garage options of the parking tariff model " + tariffsPath + " ("
					+ ParkingConfigGroup.GROUP_NAME + ".tariffsPath, garage_decay_m " + tariffs.garageDecayMeters()
					+ " m, garage_decay_commute_m " + tariffs.garageCommuteDecayMeters() + " m) need to find the garages within "
					+ tariffs.garageMaximumDistanceMeters() + " m; write the plans with activity coordinates (EPSG:25832) or set"
					+ " garage_decay_m and garage_decay_commute_m to 0 to switch the garage options off");
		}
	}

	/**
	 * Whether the activity carries parkingFree = true; fails for a mistyped value and for free parking outside every
	 * zone.
	 */
	private boolean parkingFree(Person person, int planIndex, int activityIndex, Activity activity, boolean inZone) {
		boolean free = ParkingAttributes.parkingFree(person, planIndex, activityIndex, activity);
		if (free && !inZone) {
			throw new IllegalStateException(ParkingAttributes.describe(person, planIndex, activityIndex, activity) + ": "
					+ ZoneParkingCarCostModel.FREE_ATTRIBUTE + " true without a " + ZoneParkingCarCostModel.ZONE_ATTRIBUTE
					+ "; the eqasim-bs plans writer never writes free parking outside the zones of the parking tariff model "
					+ tariffsPath + " (" + ParkingConfigGroup.GROUP_NAME + ".tariffsPath), so the plans come from a"
					+ " defective writer");
		}
		return free;
	}

	/**
	 * The tariff of the person's residentParkingZone, or null for a person living in no resident zone; fails for a
	 * mistyped value, an unknown zone and a zone of another type than resident_zone.
	 */
	private ZoneTariff residentZone(Person person) {
		ZoneTariff zone = ParkingAttributes.residentZone(tariffs, tariffsPath, person);
		if (zone != null && zone.zoneType() != ZoneType.RESIDENT_ZONE) {
			throw new IllegalStateException(ParkingAttributes.describe(person) + ": "
					+ ZoneParkingCarCostModel.RESIDENT_ATTRIBUTE + " " + zone.zoneId() + " is a " + zone.zoneType().jsonName()
					+ " zone of the parking tariff model " + tariffsPath + " (" + ParkingConfigGroup.GROUP_NAME
					+ ".tariffsPath), not a " + ZoneType.RESIDENT_ZONE.jsonName()
					+ "; only a resident zone exempts its residents");
		}
		return zone;
	}

	private IllegalStateException legacyRing(String owner, Object value) {
		return new IllegalStateException(owner + ": " + LEGACY_RING_ATTRIBUTE + " " + value + " belongs to the legacy 8 km"
				+ " ring parking (Python flag enable_urban_parking), which is mutually exclusive with the zone-based parking"
				+ " costs of this run (" + ParkingConfigGroup.GROUP_NAME + ", Python flag parking_zones_enabled, parking"
				+ " tariff model " + tariffsPath + "); write the plans with enable_urban_parking = false");
	}

	private static double percent(long count, long total) {
		return total == 0 ? 0.0 : 100.0 * count / total;
	}
}
