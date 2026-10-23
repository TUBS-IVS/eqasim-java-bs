package org.eqasim.braunschweig.scenario;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.utils.collections.QuadTree;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.ActivityFacilityImpl;
import org.matsim.facilities.ActivityOption;

/**
 * Road access of large sites (issue #465, ADR-0165).
 *
 * <p>
 * eqasim attaches every facility to its single nearest link whatever the link's capacity, so all car departures of a
 * large site enter the network on one link. This class works on SITES: all facilities at the same coordinate (key: x,
 * y to 0.01 m, written {@code <x>_<y>}), because incommuter workplaces ({@code ic_work_<person>}, one activity each) are exact copies of the
 * coordinate of their workplace. Facilities whose id starts with an excluded prefix (portals, external centroids,
 * homes) are never part of a site. Size = activities at the site's facilities in the selected plans / sample size
 * (stated at 100 %); a site is large from {@link Parameters#minimumActivities()}.
 * </p>
 * <ul>
 * <li><b>In scope</b> (work sites and universities, owner decision of 2026-10-23): {@code work} activities make at
 * least {@link Parameters#scopeWorkShare()} of the site's activities, or a facility id starts with a scope prefix.
 * Such a site takes parking lots nearest-first (ties: lot id) within {@link Parameters#parkingMaximumDistanceMeters()}
 * until their spaces reach {@link Parameters#parkingDemandShare()} x size (ASSUMPTION of the owner: half of the
 * activities park at the same time). The access link of a lot is the closest link of at least the minimum capacity
 * within {@link Parameters#lotLinkMaximumDistanceMeters()}, else the closest eligible link of any capacity within that
 * distance, else the lot is skipped (counted). Lots on the same link form one access group. Every person with an
 * activity at the site (any plan) gets one group ({@link AccessGroupAllocation}); a facility keeps its id for the group
 * with most of its persons and gets a sub-facility {@code <id>__access_<n>} (same coordinate, copied activity options
 * and attributes, attribute {@link #PARENT_ATTRIBUTE}) for each other group; the persons' activities at the site take
 * their group's facility and link.</li>
 * <li><b>Out of scope, or in scope without a usable lot:</b> the single-link rule of ADR-0165, per site: kept when the
 * link of its facility with most activities has the minimum capacity ({@code ADEQUATE}); else every facility of the
 * site and every activity at it moves to the closest link of the minimum capacity within
 * {@link Parameters#singleLinkMaximumDistanceMeters()} ({@code MOVED}); else kept ({@code FALLBACK}). An adequate
 * site's facilities on another link join the representative's link (one link per site).</li>
 * </ul>
 * <p>
 * Legs next to a changed activity lose their route (the population is routed afterwards). The activity coordinates do
 * not change. Deterministic: sites in key order, facilities in id order, allocation without random numbers. Modifies
 * the facilities and the population in place.
 * </p>
 */
public final class LargeSiteAccessAssignment {
	/** Attribute of a sub-facility: the id of the facility it was split from. */
	public static final String PARENT_ATTRIBUTE = "largeSiteParentFacilityId";
	static final String SUB_FACILITY_SEPARATOR = "__access_";
	static final String WORK_ACTIVITY_TYPE = "work";

	public enum Outcome {
		DISTRIBUTED, DISTRIBUTED_PARTIAL, ADEQUATE, MOVED, FALLBACK
	}

	/**
	 * @param sampleSize                       population sample size in (0, 1]
	 * @param minimumActivities                smallest site size (activities at 100 %) the rule applies to
	 * @param minimumCapacityVehiclesPerHour   minimum capacity of a single access link and of a lot's preferred link
	 * @param singleLinkMaximumDistanceMeters  search distance of the single-link rule (metres)
	 * @param parkingDemandShare               spaces to cover per activity at 100 % (ASSUMPTION 0.5)
	 * @param parkingMaximumDistanceMeters     largest site-to-lot distance (metres)
	 * @param lotLinkMaximumDistanceMeters     largest lot-to-link distance (metres)
	 * @param scopeWorkShare                   work share from which a site is in scope, in [0, 1]
	 * @param scopeFacilityPrefixes            facility id prefixes that put a site in scope (universities)
	 * @param excludedFacilityPrefixes         facility id prefixes never part of a site
	 */
	public record Parameters(double sampleSize, double minimumActivities, double minimumCapacityVehiclesPerHour,
			double singleLinkMaximumDistanceMeters, double parkingDemandShare, double parkingMaximumDistanceMeters,
			double lotLinkMaximumDistanceMeters, double scopeWorkShare, List<String> scopeFacilityPrefixes,
			List<String> excludedFacilityPrefixes) {
		public Parameters {
			require(sampleSize > 0.0 && sampleSize <= 1.0, "sampleSize must lie in (0, 1], got " + sampleSize);
			require(minimumActivities >= 0.0 && Double.isFinite(minimumActivities),
					"minimumActivities must be a finite number >= 0, got " + minimumActivities);
			require(minimumCapacityVehiclesPerHour >= 0.0 && Double.isFinite(minimumCapacityVehiclesPerHour),
					"minimumCapacityVehiclesPerHour must be a finite number >= 0, got " + minimumCapacityVehiclesPerHour);
			require(singleLinkMaximumDistanceMeters > 0.0 && Double.isFinite(singleLinkMaximumDistanceMeters),
					"singleLinkMaximumDistanceMeters must be a finite number > 0, got " + singleLinkMaximumDistanceMeters);
			require(parkingDemandShare > 0.0 && parkingDemandShare <= 10.0,
					"parkingDemandShare must lie in (0, 10], got " + parkingDemandShare);
			require(parkingMaximumDistanceMeters > 0.0 && Double.isFinite(parkingMaximumDistanceMeters),
					"parkingMaximumDistanceMeters must be a finite number > 0, got " + parkingMaximumDistanceMeters);
			require(lotLinkMaximumDistanceMeters > 0.0 && Double.isFinite(lotLinkMaximumDistanceMeters),
					"lotLinkMaximumDistanceMeters must be a finite number > 0, got " + lotLinkMaximumDistanceMeters);
			require(scopeWorkShare >= 0.0 && scopeWorkShare <= 1.0, "scopeWorkShare must lie in [0, 1], got " + scopeWorkShare);
			scopeFacilityPrefixes = List.copyOf(scopeFacilityPrefixes);
			excludedFacilityPrefixes = List.copyOf(excludedFacilityPrefixes);
		}

		private static void require(boolean condition, String message) {
			if (!condition) {
				throw new IllegalArgumentException(message);
			}
		}
	}

	/**
	 * One large site and what happened to it. {@code newLinkId}, {@code newLinkCapacityVehiclesPerHour} and
	 * {@code distanceMeters} are set for {@code MOVED} only; the lot fields describe the lots used (0 / null without).
	 */
	public record SiteDecision(String siteKey, Coord coord, int facilities, int activitiesInSample,
			double activities100Percent, boolean inScope, Outcome outcome, Id<Link> oldLinkId,
			double oldLinkCapacityVehiclesPerHour, Id<Link> newLinkId, Double newLinkCapacityVehiclesPerHour,
			Double distanceMeters, int lotsUsed, int accessGroups, int spacesUsed, double coverageRatio,
			Double spacesWeightedDistanceMeters, Double maximumLotDistanceMeters) {
	}

	/**
	 * One access group of a distributed site: its link, its lots and spaces, the persons allocated to it and the
	 * facilities (original or sub-facility) whose activities use its link.
	 */
	public record AccessGroupRecord(String siteKey, int groupNumber, Id<Link> linkId, double linkCapacityVehiclesPerHour,
			List<String> lotIds, int spaces, int persons, List<String> facilityIds) {
	}

	/**
	 * All decisions in site-key order, all access groups, and the counters: sub-facilities created, persons reassigned
	 * to a group, routes removed, lots used per access-link rule, lots skipped for lack of a link (per site), and
	 * facilities with an excluded prefix that alone would be large.
	 */
	public record Result(List<SiteDecision> sites, List<AccessGroupRecord> groups, int subFacilitiesCreated,
			int personsReassigned, int removedRoutes, int lotsOnCapacityRule, int lotsOnNearestRule, int lotsSkippedNoLink,
			int excludedLargeFacilities) {
		public long count(Outcome outcome) {
			return sites.stream().filter(site -> site.outcome() == outcome).count();
		}

		public long inScopeSites() {
			return sites.stream().filter(SiteDecision::inScope).count();
		}

		/** In-scope sites that found no usable lot within the radius (they took the single-link rule). */
		public long inScopeWithoutLot() {
			return sites.stream().filter(site -> site.inScope() && site.lotsUsed() == 0).count();
		}
	}

	/** {@code representative}: the facility with most selected-plan activities (ties: lower id). */
	private record Site(String key, Coord coord, List<ActivityFacility> facilities, int activities, int workActivities,
			double activities100Percent, ActivityFacility representative) {
	}

	private record LotAccess(Link link, boolean capacityRule) {
	}

	private static final class GroupBuilder {
		final Link link;
		final List<String> lotIds = new ArrayList<>();
		int spaces;

		GroupBuilder(Link link) {
			this.link = link;
		}
	}

	private record LotSelection(List<GroupBuilder> groups, int spaces, int lotsUsed, double spacesWeightedDistanceMeters,
			double maximumDistanceMeters, int onCapacityRule, int onNearestRule, int skipped) {
	}

	private final Network network;
	private final Parameters parameters;
	private final QuadTree<ParkingLot> lotIndex;
	private final LargeFacilityAccessLinkSelector singleLinkSelector;
	private final LargeFacilityAccessLinkSelector lotCapacitySelector;
	private final LargeFacilityAccessLinkSelector lotNearestSelector;
	private final Map<String, Optional<LotAccess>> lotAccess = new HashMap<>();

	public LargeSiteAccessAssignment(Network network, Parameters parameters, List<ParkingLot> lots) {
		this.network = network;
		this.parameters = parameters;
		this.singleLinkSelector = new LargeFacilityAccessLinkSelector(network, parameters.minimumCapacityVehiclesPerHour(),
				parameters.singleLinkMaximumDistanceMeters());
		this.lotCapacitySelector = new LargeFacilityAccessLinkSelector(network, parameters.minimumCapacityVehiclesPerHour(),
				parameters.lotLinkMaximumDistanceMeters());
		this.lotNearestSelector = new LargeFacilityAccessLinkSelector(network, 0.0, parameters.lotLinkMaximumDistanceMeters());
		this.lotIndex = lots.isEmpty() ? null : index(lots);
	}

	private static QuadTree<ParkingLot> index(List<ParkingLot> lots) {
		double minX = Double.POSITIVE_INFINITY;
		double minY = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY;
		double maxY = Double.NEGATIVE_INFINITY;
		for (ParkingLot lot : lots) {
			minX = Math.min(minX, lot.coord().getX());
			minY = Math.min(minY, lot.coord().getY());
			maxX = Math.max(maxX, lot.coord().getX());
			maxY = Math.max(maxY, lot.coord().getY());
		}
		// One metre of margin keeps the tree valid when all lots share one coordinate.
		QuadTree<ParkingLot> tree = new QuadTree<>(minX - 1.0, minY - 1.0, maxX + 1.0, maxY + 1.0);
		for (ParkingLot lot : lots) {
			tree.put(lot.coord().getX(), lot.coord().getY(), lot);
		}
		return tree;
	}

	public Result run(ActivityFacilities facilities, Population population) {
		Map<Id<ActivityFacility>, int[]> counts = countSelectedActivities(population);

		Map<String, List<ActivityFacility>> byKey = new TreeMap<>();
		int excludedLargeFacilities = 0;
		for (ActivityFacility facility : facilities.getFacilities().values()) {
			if (isExcluded(facility.getId().toString())) {
				int[] count = counts.get(facility.getId());
				if (count != null && count[0] / parameters.sampleSize() >= parameters.minimumActivities()) {
					excludedLargeFacilities++;
				}
				continue;
			}
			byKey.computeIfAbsent(siteKey(facility.getCoord()), key -> new ArrayList<>()).add(facility);
		}

		List<Site> sites = new ArrayList<>();
		Map<Id<ActivityFacility>, Site> siteOf = new HashMap<>();
		for (Map.Entry<String, List<ActivityFacility>> entry : byKey.entrySet()) {
			int activities = 0;
			int work = 0;
			for (ActivityFacility facility : entry.getValue()) {
				int[] count = counts.get(facility.getId());
				if (count != null) {
					activities += count[0];
					work += count[1];
				}
			}
			double size = activities / parameters.sampleSize();
			if (activities == 0 || size < parameters.minimumActivities()) {
				continue;
			}
			List<ActivityFacility> members = new ArrayList<>(entry.getValue());
			members.sort(Comparator.comparing(facility -> facility.getId().toString()));
			ActivityFacility representative = null;
			int representativeCount = -1;
			for (ActivityFacility facility : members) {
				int[] count = counts.get(facility.getId());
				int value = count == null ? 0 : count[0];
				if (value > representativeCount) {
					representative = facility;
					representativeCount = value;
				}
			}
			Site site = new Site(entry.getKey(), members.get(0).getCoord(), members, activities, work, size,
					representative);
			sites.add(site);
			members.forEach(facility -> siteOf.put(facility.getId(), site));
		}

		// Persons per site and per facility over ALL plans (a person with the site in any plan gets a group).
		Map<String, Set<Id<Person>>> sitePersons = new HashMap<>();
		Map<Id<ActivityFacility>, Set<Id<Person>>> facilityPersons = new HashMap<>();
		for (Person person : population.getPersons().values()) {
			for (Plan plan : person.getPlans()) {
				for (PlanElement element : plan.getPlanElements()) {
					if (element instanceof Activity activity && activity.getFacilityId() != null) {
						Site site = siteOf.get(activity.getFacilityId());
						if (site != null) {
							sitePersons.computeIfAbsent(site.key(), key -> new LinkedHashSet<>()).add(person.getId());
							facilityPersons.computeIfAbsent(activity.getFacilityId(), key -> new LinkedHashSet<>())
									.add(person.getId());
						}
					}
				}
			}
		}

		List<SiteDecision> decisions = new ArrayList<>();
		List<AccessGroupRecord> groups = new ArrayList<>();
		Counters counters = new Counters();
		for (Site site : sites) {
			boolean inScope = isInScope(site);
			LotSelection selection = inScope ? selectLots(site) : null;
			if (selection != null) {
				counters.onCapacityRule += selection.onCapacityRule();
				counters.onNearestRule += selection.onNearestRule();
				counters.skipped += selection.skipped();
			}
			if (selection != null && !selection.groups().isEmpty()) {
				decisions.add(distribute(site, selection, facilities, population,
						sitePersons.getOrDefault(site.key(), Set.of()), facilityPersons, groups, counters));
			} else {
				decisions.add(singleLink(site, inScope, population, sitePersons.getOrDefault(site.key(), Set.of()),
						counters));
			}
		}
		return new Result(decisions, groups, counters.subFacilities, counters.personsReassigned, counters.removedRoutes,
				counters.onCapacityRule, counters.onNearestRule, counters.skipped, excludedLargeFacilities);
	}

	private static final class Counters {
		int subFacilities;
		int personsReassigned;
		int removedRoutes;
		int onCapacityRule;
		int onNearestRule;
		int skipped;
	}

	/** [all activities, work activities] per facility in the selected plans. */
	private static Map<Id<ActivityFacility>, int[]> countSelectedActivities(Population population) {
		Map<Id<ActivityFacility>, int[]> counts = new HashMap<>();
		for (Person person : population.getPersons().values()) {
			for (PlanElement element : person.getSelectedPlan().getPlanElements()) {
				if (element instanceof Activity activity && activity.getFacilityId() != null) {
					int[] count = counts.computeIfAbsent(activity.getFacilityId(), key -> new int[2]);
					count[0]++;
					if (WORK_ACTIVITY_TYPE.equals(activity.getType())) {
						count[1]++;
					}
				}
			}
		}
		return counts;
	}

	static String siteKey(Coord coord) {
		return String.format(Locale.ROOT, "%.2f_%.2f", coord.getX(), coord.getY());
	}

	private boolean isExcluded(String facilityId) {
		return parameters.excludedFacilityPrefixes().stream().anyMatch(facilityId::startsWith);
	}

	private boolean isInScope(Site site) {
		if ((double) site.workActivities() / site.activities() >= parameters.scopeWorkShare()) {
			return true;
		}
		return site.facilities().stream().map(facility -> facility.getId().toString())
				.anyMatch(id -> parameters.scopeFacilityPrefixes().stream().anyMatch(id::startsWith));
	}

	private LotSelection selectLots(Site site) {
		if (lotIndex == null) {
			return new LotSelection(List.of(), 0, 0, 0.0, 0.0, 0, 0, 0);
		}
		Collection<ParkingLot> near = lotIndex.getDisk(site.coord().getX(), site.coord().getY(),
				parameters.parkingMaximumDistanceMeters());
		List<ParkingLot> ordered = new ArrayList<>(near);
		ordered.sort(Comparator.comparingDouble((ParkingLot lot) -> CoordUtils.calcEuclideanDistance(site.coord(), lot.coord()))
				.thenComparing(ParkingLot::lotId));
		double needed = parameters.parkingDemandShare() * site.activities100Percent();
		Map<Id<Link>, GroupBuilder> groups = new LinkedHashMap<>();
		int spaces = 0;
		int used = 0;
		int onCapacityRule = 0;
		int onNearestRule = 0;
		int skipped = 0;
		double weightedDistance = 0.0;
		double maximumDistance = 0.0;
		for (ParkingLot lot : ordered) {
			if (spaces >= needed) {
				break;
			}
			Optional<LotAccess> access = lotAccess.computeIfAbsent(lot.lotId(), id -> accessOf(lot));
			if (access.isEmpty()) {
				skipped++;
				continue;
			}
			double distance = CoordUtils.calcEuclideanDistance(site.coord(), lot.coord());
			GroupBuilder group = groups.computeIfAbsent(access.get().link().getId(), id -> new GroupBuilder(access.get().link()));
			group.lotIds.add(lot.lotId());
			group.spaces += lot.spaces();
			spaces += lot.spaces();
			used++;
			weightedDistance += lot.spaces() * distance;
			maximumDistance = Math.max(maximumDistance, distance);
			if (access.get().capacityRule()) {
				onCapacityRule++;
			} else {
				onNearestRule++;
			}
		}
		return new LotSelection(new ArrayList<>(groups.values()), spaces, used, spaces > 0 ? weightedDistance / spaces : 0.0,
				maximumDistance, onCapacityRule, onNearestRule, skipped);
	}

	private Optional<LotAccess> accessOf(ParkingLot lot) {
		Optional<Link> preferred = lotCapacitySelector.select(lot.coord());
		if (preferred.isPresent()) {
			return Optional.of(new LotAccess(preferred.get(), true));
		}
		return lotNearestSelector.select(lot.coord()).map(link -> new LotAccess(link, false));
	}

	private SiteDecision distribute(Site site, LotSelection selection, ActivityFacilities facilities, Population population,
			Set<Id<Person>> persons, Map<Id<ActivityFacility>, Set<Id<Person>>> facilityPersons,
			List<AccessGroupRecord> records, Counters counters) {
		List<GroupBuilder> groups = selection.groups();
		int[] weights = groups.stream().mapToInt(group -> group.spaces).toArray();
		Map<Id<Person>, Integer> groupOf = AccessGroupAllocation.allocate(site.key(), new ArrayList<>(persons), weights);
		Link representative = representativeLink(site);

		Map<Id<ActivityFacility>, Map<Integer, Id<ActivityFacility>>> target = new HashMap<>();
		List<Set<String>> groupFacilities = new ArrayList<>();
		groups.forEach(group -> groupFacilities.add(new TreeSet<>()));
		for (ActivityFacility facility : site.facilities()) {
			Set<Id<Person>> itsPersons = facilityPersons.getOrDefault(facility.getId(), Set.of());
			if (itsPersons.isEmpty()) {
				continue;
			}
			int[] perGroup = new int[groups.size()];
			itsPersons.forEach(person -> perGroup[groupOf.get(person)]++);
			int majority = 0;
			for (int group = 1; group < perGroup.length; group++) {
				if (perGroup[group] > perGroup[majority]) {
					majority = group;
				}
			}
			Map<Integer, Id<ActivityFacility>> byGroup = new HashMap<>();
			for (int group = 0; group < groups.size(); group++) {
				if (perGroup[group] == 0) {
					continue;
				}
				Id<ActivityFacility> id;
				if (group == majority) {
					((ActivityFacilityImpl) facility).setLinkId(groups.get(group).link.getId());
					id = facility.getId();
				} else {
					id = createSubFacility(facilities, facility, group + 1, groups.get(group).link.getId());
					counters.subFacilities++;
				}
				byGroup.put(group, id);
				groupFacilities.get(group).add(id.toString());
			}
			target.put(facility.getId(), byGroup);
		}

		int[] groupPersons = new int[groups.size()];
		for (Id<Person> personId : persons) {
			int group = groupOf.get(personId);
			groupPersons[group]++;
			Id<Link> link = groups.get(group).link.getId();
			for (Plan plan : population.getPersons().get(personId).getPlans()) {
				List<PlanElement> elements = plan.getPlanElements();
				for (int index = 0; index < elements.size(); index++) {
					if (elements.get(index) instanceof Activity activity && activity.getFacilityId() != null) {
						Map<Integer, Id<ActivityFacility>> byGroup = target.get(activity.getFacilityId());
						if (byGroup == null) {
							continue;
						}
						activity.setFacilityId(byGroup.get(group));
						activity.setLinkId(link);
						counters.removedRoutes += removeRoute(elements, index - 1) + removeRoute(elements, index + 1);
					}
				}
			}
			counters.personsReassigned++;
		}

		for (int group = 0; group < groups.size(); group++) {
			GroupBuilder builder = groups.get(group);
			records.add(new AccessGroupRecord(site.key(), group + 1, builder.link.getId(),
					singleLinkSelector.capacityVehiclesPerHour(builder.link), List.copyOf(builder.lotIds), builder.spaces,
					groupPersons[group], List.copyOf(groupFacilities.get(group))));
		}
		double needed = parameters.parkingDemandShare() * site.activities100Percent();
		boolean partial = selection.spaces() < needed;
		return new SiteDecision(site.key(), site.coord(), site.facilities().size(), site.activities(),
				site.activities100Percent(), true, partial ? Outcome.DISTRIBUTED_PARTIAL : Outcome.DISTRIBUTED,
				representative.getId(), singleLinkSelector.capacityVehiclesPerHour(representative), null, null, null,
				selection.lotsUsed(), groups.size(), selection.spaces(), Math.min(1.0, selection.spaces() / needed),
				selection.spacesWeightedDistanceMeters(), selection.maximumDistanceMeters());
	}

	private Id<ActivityFacility> createSubFacility(ActivityFacilities facilities, ActivityFacility facility, int groupNumber,
			Id<Link> linkId) {
		Id<ActivityFacility> id = Id.create(facility.getId() + SUB_FACILITY_SEPARATOR + groupNumber, ActivityFacility.class);
		if (facilities.getFacilities().containsKey(id)) {
			throw new IllegalStateException("sub-facility id " + id + " already exists in the facilities");
		}
		ActivityFacility sub = facilities.getFactory().createActivityFacility(id, facility.getCoord(), linkId);
		for (ActivityOption option : facility.getActivityOptions().values()) {
			ActivityOption copy = facilities.getFactory().createActivityOption(option.getType());
			copy.setCapacity(option.getCapacity());
			option.getOpeningTimes().forEach(copy::addOpeningTime);
			sub.addActivityOption(copy);
		}
		facility.getAttributes().getAsMap().forEach(sub.getAttributes()::putAttribute);
		sub.getAttributes().putAttribute(PARENT_ATTRIBUTE, facility.getId().toString());
		facilities.addActivityFacility(sub);
		return id;
	}

	private SiteDecision singleLink(Site site, boolean inScope, Population population, Set<Id<Person>> persons,
			Counters counters) {
		Link old = representativeLink(site);
		double oldCapacity = singleLinkSelector.capacityVehiclesPerHour(old);
		if (singleLinkSelector.hasMinimumCapacity(old)) {
			// One link per site: facilities of the site on another link join the representative's link.
			relinkSite(site, old, population, persons, counters);
			return decision(site, inScope, Outcome.ADEQUATE, old, oldCapacity, null);
		}
		Optional<Link> selected = singleLinkSelector.select(site.coord());
		if (selected.isEmpty()) {
			return decision(site, inScope, Outcome.FALLBACK, old, oldCapacity, null);
		}
		relinkSite(site, selected.get(), population, persons, counters);
		return decision(site, inScope, Outcome.MOVED, old, oldCapacity, selected.get());
	}

	/** Puts every facility of the site that is not on {@code link} and every activity at it onto {@code link}. */
	private static void relinkSite(Site site, Link link, Population population, Set<Id<Person>> persons, Counters counters) {
		Set<Id<ActivityFacility>> members = new TreeSet<>(Comparator.comparing(Id::toString));
		for (ActivityFacility facility : site.facilities()) {
			if (!link.getId().equals(facility.getLinkId())) {
				((ActivityFacilityImpl) facility).setLinkId(link.getId());
				members.add(facility.getId());
			}
		}
		if (members.isEmpty()) {
			return;
		}
		for (Id<Person> personId : persons) {
			for (Plan plan : population.getPersons().get(personId).getPlans()) {
				List<PlanElement> elements = plan.getPlanElements();
				for (int index = 0; index < elements.size(); index++) {
					if (elements.get(index) instanceof Activity activity && activity.getFacilityId() != null
							&& members.contains(activity.getFacilityId())) {
						activity.setLinkId(link.getId());
						counters.removedRoutes += removeRoute(elements, index - 1) + removeRoute(elements, index + 1);
					}
				}
			}
		}
	}

	private SiteDecision decision(Site site, boolean inScope, Outcome outcome, Link old, double oldCapacity, Link moved) {
		return new SiteDecision(site.key(), site.coord(), site.facilities().size(), site.activities(),
				site.activities100Percent(), inScope, outcome, old.getId(), oldCapacity, moved == null ? null : moved.getId(),
				moved == null ? null : singleLinkSelector.capacityVehiclesPerHour(moved),
				moved == null ? null : LargeFacilityAccessLinkSelector.distanceMeters(moved, site.coord()), 0, 0, 0, 0.0, null,
				null);
	}

	/** Link of the site's representative facility; read before the site is changed. */
	private Link representativeLink(Site site) {
		ActivityFacility facility = site.representative();
		Link link = network.getLinks().get(facility.getLinkId());
		if (link == null) {
			throw new IllegalStateException("facility " + facility.getId() + " references link " + facility.getLinkId()
					+ ", which is missing from the network");
		}
		return link;
	}

	private static int removeRoute(List<PlanElement> elements, int index) {
		if (index < 0 || index >= elements.size() || !(elements.get(index) instanceof Leg leg) || leg.getRoute() == null) {
			return 0;
		}
		leg.setRoute(null);
		return 1;
	}
}
