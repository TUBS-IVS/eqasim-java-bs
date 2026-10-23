package org.eqasim.braunschweig.scenario;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.ActivityFacilityImpl;

/**
 * Moves large facilities that sit on a low-capacity link to a high-capacity access link (issue #465, ADR-0165).
 *
 * <p>
 * Size is the number of activities at the facility in the selected plans, divided by the population sample
 * size, so the threshold is stated for a 100 % population. ASSUMPTION: the activity count is a proxy for the
 * car departures the facility generates; in a small sample it is noisy (at 1 %, 10 activities stand for
 * 1000), so the set of moved facilities varies with the sample. For each facility of at least
 * {@code minimumActivities} that does not start with an excluded prefix:
 * </p>
 * <ul>
 * <li>{@link Outcome#ADEQUATE}: its link already has the minimum capacity; nothing changes;</li>
 * <li>{@link Outcome#MOVED}: the selector found a link; the facility and every activity at it get that link,
 * and the routes of legs that start or end at such an activity are removed (they referenced the old link;
 * the population is routed afterwards);</li>
 * <li>{@link Outcome#FALLBACK}: no eligible link within the maximum distance; the facility keeps its link.</li>
 * </ul>
 * <p>
 * Facilities with an excluded prefix are reported as {@link Outcome#EXCLUDED} and never changed. Modifies the
 * facilities and the population in place.
 * </p>
 */
public final class LargeFacilityAccessAssignment {
	public enum Outcome {
		ADEQUATE, MOVED, FALLBACK, EXCLUDED
	}

	/** One large facility and what happened to it; new link fields are null unless the facility moved. */
	public record Decision(Id<ActivityFacility> facilityId, int activitiesInSample, double activities100Percent,
			Id<Link> oldLinkId, double oldLinkCapacityVehiclesPerHour, Outcome outcome, Id<Link> newLinkId,
			Double newLinkCapacityVehiclesPerHour, Double distanceMeters) {
	}

	/** All decisions, sorted by facility id, plus the number of leg routes removed. */
	public record Result(List<Decision> decisions, int removedRoutes) {
		public long count(Outcome outcome) {
			return decisions.stream().filter(decision -> decision.outcome() == outcome).count();
		}
	}

	private final Network network;
	private final LargeFacilityAccessLinkSelector selector;
	private final double sampleSize;
	private final double minimumActivities;
	private final Collection<String> excludedPrefixes;

	/**
	 * @param network           network that holds the facilities' current links
	 * @param selector          access-link rule
	 * @param sampleSize        population sample size in (0, 1]
	 * @param minimumActivities smallest facility size (activities at 100 %) the rule applies to; non-negative
	 * @param excludedPrefixes  facility id prefixes the rule never touches (the cordon portals)
	 */
	public LargeFacilityAccessAssignment(Network network, LargeFacilityAccessLinkSelector selector, double sampleSize,
			double minimumActivities, Collection<String> excludedPrefixes) {
		if (!(sampleSize > 0.0 && sampleSize <= 1.0)) {
			throw new IllegalArgumentException("sampleSize must lie in (0, 1], got " + sampleSize);
		}
		if (!(minimumActivities >= 0.0) || Double.isInfinite(minimumActivities)) {
			throw new IllegalArgumentException(
					"minimumActivities must be a non-negative finite number, got " + minimumActivities);
		}
		this.network = network;
		this.selector = selector;
		this.sampleSize = sampleSize;
		this.minimumActivities = minimumActivities;
		this.excludedPrefixes = List.copyOf(excludedPrefixes);
	}

	public Result run(ActivityFacilities facilities, Population population) {
		Map<Id<ActivityFacility>, Integer> activityCounts = countActivities(population);
		List<Decision> decisions = new ArrayList<>();
		Map<Id<ActivityFacility>, Id<Link>> newLinks = new HashMap<>();
		for (Map.Entry<Id<ActivityFacility>, Integer> entry : activityCounts.entrySet()) {
			double activities100Percent = entry.getValue() / sampleSize;
			if (activities100Percent < minimumActivities) {
				continue;
			}
			ActivityFacility facility = facilities.getFacilities().get(entry.getKey());
			if (facility == null) {
				throw new IllegalStateException("activities reference facility " + entry.getKey()
						+ ", which is missing from the facilities file");
			}
			Link oldLink = network.getLinks().get(facility.getLinkId());
			if (oldLink == null) {
				throw new IllegalStateException(
						"facility " + facility.getId() + " references link " + facility.getLinkId() + ", which is missing from the network");
			}
			Decision decision = decide(facility, entry.getValue(), activities100Percent, oldLink);
			decisions.add(decision);
			if (decision.outcome() == Outcome.MOVED) {
				((ActivityFacilityImpl) facility).setLinkId(decision.newLinkId());
				newLinks.put(facility.getId(), decision.newLinkId());
			}
		}
		int removedRoutes = relinkActivities(population, newLinks);
		return new Result(decisions, removedRoutes);
	}

	private Decision decide(ActivityFacility facility, int activitiesInSample, double activities100Percent,
			Link oldLink) {
		double oldCapacity = selector.capacityVehiclesPerHour(oldLink);
		String id = facility.getId().toString();
		if (excludedPrefixes.stream().anyMatch(id::startsWith)) {
			return new Decision(facility.getId(), activitiesInSample, activities100Percent, oldLink.getId(), oldCapacity,
					Outcome.EXCLUDED, null, null, null);
		}
		if (selector.hasMinimumCapacity(oldLink)) {
			return new Decision(facility.getId(), activitiesInSample, activities100Percent, oldLink.getId(), oldCapacity,
					Outcome.ADEQUATE, null, null, null);
		}
		Optional<Link> selected = selector.select(facility.getCoord());
		if (selected.isEmpty()) {
			return new Decision(facility.getId(), activitiesInSample, activities100Percent, oldLink.getId(), oldCapacity,
					Outcome.FALLBACK, null, null, null);
		}
		Link newLink = selected.get();
		return new Decision(facility.getId(), activitiesInSample, activities100Percent, oldLink.getId(), oldCapacity,
				Outcome.MOVED, newLink.getId(), selector.capacityVehiclesPerHour(newLink),
				LargeFacilityAccessLinkSelector.distanceMeters(newLink, facility.getCoord()));
	}

	/** Activities per facility in the selected plans, sorted by facility id (deterministic decision order). */
	private static Map<Id<ActivityFacility>, Integer> countActivities(Population population) {
		Map<Id<ActivityFacility>, Integer> counts = new TreeMap<>(Comparator.comparing(Id::toString));
		for (Person person : population.getPersons().values()) {
			for (PlanElement element : person.getSelectedPlan().getPlanElements()) {
				if (element instanceof Activity activity && activity.getFacilityId() != null) {
					counts.merge(activity.getFacilityId(), 1, Integer::sum);
				}
			}
		}
		return counts;
	}

	/** Gives every activity at a moved facility its new link (all plans); returns the number of removed routes. */
	private static int relinkActivities(Population population, Map<Id<ActivityFacility>, Id<Link>> newLinks) {
		if (newLinks.isEmpty()) {
			return 0;
		}
		int removedRoutes = 0;
		for (Person person : population.getPersons().values()) {
			for (Plan plan : person.getPlans()) {
				List<PlanElement> elements = plan.getPlanElements();
				for (int index = 0; index < elements.size(); index++) {
					if (!(elements.get(index) instanceof Activity activity)) {
						continue;
					}
					Id<Link> newLink = activity.getFacilityId() == null ? null : newLinks.get(activity.getFacilityId());
					if (newLink == null) {
						continue;
					}
					activity.setLinkId(newLink);
					removedRoutes += removeRoute(elements, index - 1) + removeRoute(elements, index + 1);
				}
			}
		}
		return removedRoutes;
	}

	private static int removeRoute(List<PlanElement> elements, int index) {
		if (index < 0 || index >= elements.size() || !(elements.get(index) instanceof Leg leg) || leg.getRoute() == null) {
			return 0;
		}
		leg.setRoute(null);
		return 1;
	}
}
