package org.eqasim.core.components.transit;

import java.util.HashSet;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.core.components.transit.departure.DepartureFinder.NoDepartureFoundException;
import org.eqasim.core.components.transit.departure.TransitBoardingFinder;
import org.eqasim.core.components.transit.departure.TransitBoardingFinder.Boarding;
import org.eqasim.core.components.transit.events.PublicTransitEvent;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.api.experimental.events.TeleportationArrivalEvent;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.framework.PlanAgent;
import org.matsim.core.mobsim.qsim.InternalInterface;
import org.matsim.core.mobsim.qsim.interfaces.AgentCounter;
import org.matsim.core.mobsim.qsim.interfaces.DepartureHandler;
import org.matsim.core.mobsim.qsim.interfaces.MobsimEngine;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;

import com.google.inject.Singleton;

@Singleton
public class EqasimTransitEngine implements DepartureHandler, MobsimEngine {
	private static final Logger logger = LogManager.getLogger(EqasimTransitEngine.class);

	final private TransitSchedule transitSchedule;
	final private TransitBoardingFinder boardingFinder;
	private InternalInterface internalInterface;
	final private EventsManager eventsManager;
	final private AgentCounter agentCounter;

	// Boarding outcomes of the current mobsim run, logged after it (eqasim-bs#465)
	private int numberOfPtDepartures = 0;
	private int numberOfToleratedBoardings = 0;
	private int numberOfLaterBoardings = 0;
	private int numberOfOtherRouteBoardings = 0;
	private int numberOfOtherLineBoardings = 0;
	private int numberOfMissingDepartures = 0;

	final private PriorityQueue<AgentDeparture> departures = new PriorityQueue<>();
	final private PriorityQueue<AgentArrival> arrivals = new PriorityQueue<>();

	private class AgentDeparture implements Comparable<AgentDeparture> {
		final public MobsimAgent agent;
		final public double departureTime;
		final public Id<Link> departureLinkId;

		public AgentDeparture(MobsimAgent agent, double departureTime, Id<Link> departureLinkId) {
			this.agent = agent;
			this.departureTime = departureTime;
			this.departureLinkId = departureLinkId;
		}

		@Override
		public int compareTo(AgentDeparture other) {
			return Double.compare(departureTime, other.departureTime);
		}
	}

	private class AgentArrival implements Comparable<AgentArrival> {
		final public MobsimAgent agent;
		final public double arrivalTime;
		final public Id<Link> arrivalLinkId;
		final public PublicTransitEvent event;

		public AgentArrival(MobsimAgent agent, double arrivalTime, Id<Link> arrivalLinkId, PublicTransitEvent event) {
			this.agent = agent;
			this.arrivalTime = arrivalTime;
			this.arrivalLinkId = arrivalLinkId;
			this.event = event;
		}

		@Override
		public int compareTo(AgentArrival other) {
			return Double.compare(arrivalTime, other.arrivalTime);
		}
	}

	public EqasimTransitEngine(EventsManager eventsManager, TransitSchedule transitSchedule,
			TransitBoardingFinder boardingFinder, AgentCounter agentCounter) {
		this.eventsManager = eventsManager;
		this.transitSchedule = transitSchedule;
		this.boardingFinder = boardingFinder;
		this.agentCounter = agentCounter;
	}

	@Override
	public boolean handleDeparture(double now, MobsimAgent agent, Id<Link> departureLinkId) {
		if (agent.getMode().equals("pt")) {
			Leg leg = (Leg) ((PlanAgent) agent).getCurrentPlanElement();
			TransitPassengerRoute route = (TransitPassengerRoute) leg.getRoute();

			TransitLine transitLine = transitSchedule.getTransitLines().get(route.getLineId());
			TransitRoute transitRoute = transitLine.getRoutes().get(route.getRouteId());

			numberOfPtDepartures++;

			try {
				Boarding boarding = boardingFinder.findBoarding(transitLine, transitRoute, route.getAccessStopId(),
						route.getEgressStopId(), now, route.getBoardingTime().seconds());

				double vehicleDepartureTime = boarding.vehicleDepartureTime;
				double inVehicleTime;

				if (boarding.onPlannedRoute) {
					// As planned by the router (unchanged behaviour)
					double waitingTime = route.getBoardingTime().seconds() - leg.getDepartureTime().seconds();
					inVehicleTime = leg.getTravelTime().seconds() - waitingTime;
				} else {
					// Another route of the line, or of another line: its own stop offsets
					inVehicleTime = boarding.inVehicleTime;

					if (boarding.onPlannedLine) {
						numberOfOtherRouteBoardings++;
					} else {
						numberOfOtherLineBoardings++;
					}
				}

				if (vehicleDepartureTime < now) {
					numberOfToleratedBoardings++;
				}

				if (vehicleDepartureTime > route.getBoardingTime().seconds()) {
					numberOfLaterBoardings++;
				}

				double arrivalTime = vehicleDepartureTime + inVehicleTime;

				// Never in the past: a tolerated boarding departed up to the tolerance before now
				if (arrivalTime < now + 1.0) {
					arrivalTime = now + 1.0;
				}

				Id<Link> arrivalLinkId = transitSchedule.getFacilities().get(route.getEgressStopId()).getLinkId();

				// The boarded line and route; the travel distance stays the planned one (same stop pair)
				PublicTransitEvent transitEvent = new PublicTransitEvent(arrivalTime, agent.getId(),
						boarding.line.getId(), boarding.route.getId(), route.getAccessStopId(), route.getEgressStopId(),
						boarding.stopDeparture.departure.getId(),
						vehicleDepartureTime, route.getDistance(), route.getBoardingTime().seconds());

				internalInterface.registerAdditionalAgentOnLink(agent);
				departures.add(new AgentDeparture(agent, vehicleDepartureTime, departureLinkId));
				arrivals.add(new AgentArrival(agent, arrivalTime, arrivalLinkId, transitEvent));
			} catch (NoDepartureFoundException e) {
				numberOfMissingDepartures++;
				eventsManager.processEvent(new PersonStuckEvent(now, agent.getId(), agent.getCurrentLinkId(), "pt"));
				agentCounter.decLiving();
			}

			return true;
		}

		return false;
	}

	@Override
	public void doSimStep(double time) {
		while (!departures.isEmpty() && departures.peek().departureTime <= time) {
			AgentDeparture departure = departures.poll();
			internalInterface.unregisterAdditionalAgentOnLink(departure.agent.getId(), departure.departureLinkId);
		}

		while (!arrivals.isEmpty() && arrivals.peek().arrivalTime <= time) {
			AgentArrival arrival = arrivals.poll();
			arrival.agent.notifyArrivalOnLinkByNonNetworkMode(arrival.arrivalLinkId);
			eventsManager.processEvent(new PublicTransitEvent(time, arrival.event));
			eventsManager.processEvent(new TeleportationArrivalEvent(time, arrival.agent.getId(),
					arrival.event.getTravelDistance(), "pt"));
			arrival.agent.endLegAndComputeNextState(time);
			internalInterface.arrangeNextAgentState(arrival.agent);
		}
	}

	@Override
	public void onPrepareSim() {
		departures.clear();
		arrivals.clear();

		numberOfPtDepartures = 0;
		numberOfToleratedBoardings = 0;
		numberOfLaterBoardings = 0;
		numberOfOtherRouteBoardings = 0;
		numberOfOtherLineBoardings = 0;
		numberOfMissingDepartures = 0;
	}

	private static String share(int count, int total) {
		return String.format(Locale.ROOT, "%d (%.2f %%)", count, total == 0 ? 0.0 : 100.0 * count / total);
	}

	/**
	 * Logs how the pt departures of this mobsim run were served: boarded within
	 * the tolerance after the vehicle left, on a later departure than planned, on
	 * another route of the planned line, on a route of another line serving the
	 * same stop pair (stop-pair fallback, eqasim-bs ADR-0161), or not at all (agent
	 * stuck).
	 */
	private void logBoardings() {
		String message = String.format(Locale.ROOT,
				"[EqasimTransitEngine] pt departures %d: boarded within the tolerance of %.1f s %s, later departure "
						+ "than planned %s, another route of the planned line %s (line fallback %s), another line "
						+ "serving the stop pair %s (stop-pair fallback %s), no departure found (agent stuck) %s",
				numberOfPtDepartures, boardingFinder.getBoardingToleranceSeconds(),
				share(numberOfToleratedBoardings, numberOfPtDepartures),
				share(numberOfLaterBoardings, numberOfPtDepartures),
				share(numberOfOtherRouteBoardings, numberOfPtDepartures),
				boardingFinder.isLineFallbackEnabled() ? "on" : "off",
				share(numberOfOtherLineBoardings, numberOfPtDepartures),
				boardingFinder.isStopPairFallbackEnabled() ? "on" : "off",
				share(numberOfMissingDepartures, numberOfPtDepartures));

		if (numberOfMissingDepartures > 0) {
			logger.warn(message);
		} else {
			logger.info(message);
		}
	}

	@Override
	public void afterSim() {
		logBoardings();

		double time = internalInterface.getMobsim().getSimTimer().getTimeOfDay();
		Set<MobsimAgent> processedAgents = new HashSet<>();

		for (AgentDeparture departure : departures) {
			eventsManager
					.processEvent(new PersonStuckEvent(time, departure.agent.getId(), departure.departureLinkId, "pt"));
			agentCounter.decLiving();
			processedAgents.add(departure.agent);
		}

		for (AgentArrival arrival : arrivals) {
			if (!processedAgents.contains(arrival.agent)) {
				eventsManager
						.processEvent(new PersonStuckEvent(time, arrival.agent.getId(), arrival.arrivalLinkId, "pt"));
				agentCounter.decLiving();
			}
		}
	}

	@Override
	public void setInternalInterface(InternalInterface internalInterface) {
		this.internalInterface = internalInterface;
	}
}
