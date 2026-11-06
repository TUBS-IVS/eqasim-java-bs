package org.eqasim.core.scenario.cutter.transit;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.core.scenario.cutter.extent.ScenarioExtent;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.population.routes.LinkNetworkRouteFactory;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.MinimalTransferTimes;
import org.matsim.pt.transitSchedule.api.MinimalTransferTimes.MinimalTransferTimesIterator;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

public class TransitScheduleCutter {
	private final static Logger log = LogManager.getLogger(TransitScheduleCutter.class);

	private final ScenarioExtent extent;
	private final Set<Id<TransitStopFacility>> hubStopIds;

	/**
	 * The index range [first, last] (both inclusive) of the stops that the cut keeps of a route's original stop
	 * sequence.
	 */
	public record KeptRange(int first, int last) {
	}

	/**
	 * Counts of stop facilities by why the cut keeps them: inside the extent, a hub outside the extent, or
	 * another outside stop (kept only because it lies between two anchors of a route).
	 */
	public record StopBreakdown(int inside, int hub, int otherOutside) {
		public int total() {
			return inside + hub + otherOutside;
		}
	}

	/**
	 * @param extent      the extent a route has to touch to be part of the region
	 * @param hubStopIds  stop facilities that anchor a route like an inside stop does (may be empty, which gives
	 *                    the cut that keeps exactly the stops between the first and the last inside stop)
	 */
	public TransitScheduleCutter(ScenarioExtent extent, Set<Id<TransitStopFacility>> hubStopIds) {
		this.extent = extent;
		this.hubStopIds = Set.copyOf(hubStopIds);
	}

	/**
	 * The one reduction of a route's stop sequence, shared by the dry pass and the real cut. A route is part of
	 * the region iff at least one of its stops lies inside the extent. It keeps the stops from its first to its
	 * last anchor, an anchor being a stop inside the extent or a hub. No range is returned for a route without
	 * an inside stop or with fewer than two kept stops.
	 */
	private Optional<KeptRange> keptRange(List<TransitRouteStop> stops) {
		boolean anyInside = false;
		int first = -1;
		int last = -1;

		for (int i = 0; i < stops.size(); i++) {
			TransitStopFacility facility = stops.get(i).getStopFacility();
			boolean inside = extent.isInside(facility.getCoord());
			anyInside |= inside;

			if (inside || hubStopIds.contains(facility.getId())) {
				if (first < 0) {
					first = i;
				}
				last = i;
			}
		}

		if (!anyInside || last - first < 1) {
			return Optional.empty();
		}

		return Optional.of(new KeptRange(first, last));
	}

	private List<TransitRouteStop> reduceStopSequence(List<TransitRouteStop> originalSequence) {
		return keptRange(originalSequence)
				.map(range -> originalSequence.subList(range.first(), range.last() + 1))
				.orElse(Collections.emptyList());
	}

	/**
	 * The dry pass of the cut: the kept index range per kept route, without changing the schedule. A route that
	 * is absent from the result is dropped by {@link #run(TransitSchedule)}.
	 */
	public Map<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> keptStopRanges(TransitSchedule schedule) {
		Map<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> result = new LinkedHashMap<>();

		for (TransitLine line : schedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				Optional<KeptRange> range = keptRange(route.getStops());

				if (range.isPresent()) {
					result.computeIfAbsent(line.getId(), id -> new LinkedHashMap<>()).put(route.getId(), range.get());
				}
			}
		}

		return result;
	}

	/**
	 * The stop facilities that the cut keeps: those of the kept sub-sequences of the given ranges, as computed by
	 * {@link #keptStopRanges(TransitSchedule)} on the same, uncut schedule.
	 */
	public static Set<Id<TransitStopFacility>> keptStopFacilityIds(TransitSchedule schedule,
			Map<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> ranges) {
		Set<Id<TransitStopFacility>> facilityIds = new HashSet<>();

		for (Map.Entry<Id<TransitLine>, Map<Id<TransitRoute>, KeptRange>> lineEntry : ranges.entrySet()) {
			TransitLine line = schedule.getTransitLines().get(lineEntry.getKey());

			for (Map.Entry<Id<TransitRoute>, KeptRange> routeEntry : lineEntry.getValue().entrySet()) {
				List<TransitRouteStop> stops = line.getRoutes().get(routeEntry.getKey()).getStops();
				KeptRange range = routeEntry.getValue();

				for (TransitRouteStop stop : stops.subList(range.first(), range.last() + 1)) {
					facilityIds.add(stop.getStopFacility().getId());
				}
			}
		}

		return facilityIds;
	}

	/**
	 * Classifies stop facilities by why the cut keeps them (inside the extent, hub outside, other outside).
	 */
	public StopBreakdown classify(Collection<TransitStopFacility> facilities) {
		int inside = 0;
		int hub = 0;
		int otherOutside = 0;

		for (TransitStopFacility facility : facilities) {
			if (extent.isInside(facility.getCoord())) {
				inside++;
			} else if (hubStopIds.contains(facility.getId())) {
				hub++;
			} else {
				otherOutside++;
			}
		}

		return new StopBreakdown(inside, hub, otherOutside);
	}

	private NetworkRoute reduceNetworkRoute(NetworkRoute originalRoute, Id<Link> startLinkId, Id<Link> endLinkId) {
		List<Id<Link>> originalLinkIds = new LinkedList<>(originalRoute.getLinkIds());
		originalLinkIds.add(0, originalRoute.getStartLinkId());
		originalLinkIds.add(originalRoute.getEndLinkId());

		int startIndex = -1;
		int endIndex = -1;

		for (int i = 0; i < originalLinkIds.size(); i++) {
			if (originalLinkIds.get(i).equals(startLinkId)) {
				startIndex = i;
				break;
			}
		}

		for (int i = originalLinkIds.size() - 1; i >= 0; i--) {
			if (originalLinkIds.get(i).equals(endLinkId)) {
				endIndex = i;
				break;
			}
		}

		if (startIndex == -1 || endIndex == -1) {
			throw new IllegalStateException();
		}

		Id<Link> reducedStartLinkId = originalLinkIds.get(startIndex);
		Id<Link> reducedEndLinkId = originalLinkIds.get(endIndex);
		List<Id<Link>> reducedLinkIds = startIndex == endIndex ? new LinkedList<>()
				: originalLinkIds.subList(startIndex + 1, endIndex);

		NetworkRoute reducedRoute = (NetworkRoute) new LinkNetworkRouteFactory().createRoute(reducedStartLinkId,
				reducedEndLinkId);
		reducedRoute.setLinkIds(reducedStartLinkId, reducedLinkIds, reducedEndLinkId);

		return reducedRoute;
	}

	private TransitRoute reduceRoute(TransitRoute originalRoute, TransitScheduleFactory factory) {
		List<TransitRouteStop> reducedStopSequence = reduceStopSequence(originalRoute.getStops());

		if (reducedStopSequence.size() < 2) {
			return null;
		} else {
			Id<Link> routeStartLinkId = reducedStopSequence.get(0).getStopFacility().getLinkId();
			Id<Link> routeEndLinkId = reducedStopSequence.get(reducedStopSequence.size() - 1).getStopFacility()
					.getLinkId();
			NetworkRoute reducedNetworkRoute = reduceNetworkRoute(originalRoute.getRoute(), routeStartLinkId,
					routeEndLinkId);

			TransitRoute reducedRoute = factory.createTransitRoute(originalRoute.getId(), reducedNetworkRoute,
					reducedStopSequence, originalRoute.getTransportMode());

			// The kept stops keep their offsets, which count from the original route's start, so the departures
			// keep their original times too. Moving them by the first kept stop's offset served every stop of a
			// route that enters the extent earlier by the travel time from its original first stop.
			for (Departure originalDeparture : originalRoute.getDepartures().values()) {
				Departure reducedDeparture = factory.createDeparture(originalDeparture.getId(),
						originalDeparture.getDepartureTime());
				reducedDeparture.setVehicleId(originalDeparture.getVehicleId());
				reducedRoute.addDeparture(reducedDeparture);
			}

			return reducedRoute;
		}
	}

	private TransitLine reduceLine(TransitLine originalLine, TransitScheduleFactory factory) {
		TransitLine reducedLine = factory.createTransitLine(originalLine.getId());
		reducedLine.setName(originalLine.getName());

		for (TransitRoute originalRoute : originalLine.getRoutes().values()) {
			TransitRoute reducedRoute = reduceRoute(originalRoute, factory);

			if (reducedRoute != null) {
				reducedLine.addRoute(reducedRoute);
			}
		}

		return reducedLine.getRoutes().size() == 0 ? null : reducedLine;
	}

	public void run(TransitSchedule schedule) {
		log.info("Cutting transit schedule ...");
		ScheduleInfo originalInfo = getInfo(schedule);

		TransitScheduleFactory factory = schedule.getFactory();

		List<TransitLine> originalLines = new LinkedList<>(schedule.getTransitLines().values());
		originalLines.forEach(schedule::removeTransitLine);

		for (TransitLine originalLine : originalLines) {
			TransitLine reducedLine = reduceLine(originalLine, factory);

			if (reducedLine != null) {
				schedule.addTransitLine(reducedLine);
			}
		}

		List<TransitStopFacility> originalFacilities = new LinkedList<>(schedule.getFacilities().values());
		Set<TransitStopFacility> reducedFacilities = new HashSet<>();

		for (TransitLine line : schedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				for (TransitRouteStop stop : route.getStops()) {
					reducedFacilities.add(stop.getStopFacility());
				}
			}
		}

		originalFacilities.forEach(schedule::removeStopFacility);
		reducedFacilities.forEach(schedule::addStopFacility);

		MinimalTransferTimes minimalTransferTimes = schedule.getMinimalTransferTimes();
		MinimalTransferTimesIterator iterator = minimalTransferTimes.iterator();
		Set<Id<TransitStopFacility>> reducedIds = reducedFacilities.stream().map(f -> f.getId())
				.collect(Collectors.toSet());

		Set<Tuple<Id<TransitStopFacility>, Id<TransitStopFacility>>> removeRelations = new HashSet<>();

		while (iterator.hasNext()) {
			iterator.next();

			if (!reducedIds.contains(iterator.getFromStopId()) || !reducedIds.contains(iterator.getToStopId())) {
				removeRelations.add(new Tuple<>(iterator.getFromStopId(), iterator.getToStopId()));
			}
		}

		removeRelations.forEach(t -> minimalTransferTimes.remove(t.getFirst(), t.getSecond()));

		ScheduleInfo finalInfo = getInfo(schedule);

		log.info("Finished cutting transit schedule.");
		log.info("  Before: " + originalInfo);
		log.info("   After: " + finalInfo);

		StopBreakdown breakdown = classify(schedule.getFacilities().values());
		log.info(String.format(Locale.ROOT,
				"[TransitScheduleCutter] kept stops %d: inside %d, hub %d, other outside %d; routes kept %d/%d (%.1f %%)",
				breakdown.total(), breakdown.inside(), breakdown.hub(), breakdown.otherOutside(),
				finalInfo.numberOfRoutes, originalInfo.numberOfRoutes,
				originalInfo.numberOfRoutes == 0 ? 0.0 : 100.0 * finalInfo.numberOfRoutes / originalInfo.numberOfRoutes));
	}

	private class ScheduleInfo {
		final public int numberOfLines;
		final public int numberOfRoutes;
		final public int numberOfStops;

		public ScheduleInfo(int numberOfLines, int numberOfRoutes, int numberOfStops) {
			this.numberOfLines = numberOfLines;
			this.numberOfRoutes = numberOfRoutes;
			this.numberOfStops = numberOfStops;
		}

		@Override
		public String toString() {
			return String.format("# Lines: % 6d, # Routes: % 6d, # Stops: % 6d", numberOfLines, numberOfRoutes,
					numberOfStops);
		}
	}

	private ScheduleInfo getInfo(TransitSchedule schedule) {
		int numberOfLines = schedule.getTransitLines().size();
		int numberOfRoutes = 0;
		int numberOfStops = schedule.getFacilities().size();

		for (TransitLine line : schedule.getTransitLines().values()) {
			numberOfRoutes += line.getRoutes().size();
		}

		return new ScheduleInfo(numberOfLines, numberOfRoutes, numberOfStops);
	}
}
