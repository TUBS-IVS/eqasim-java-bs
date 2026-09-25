package org.eqasim.braunschweig.fares.zonal;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.facilities.Facility;
import org.matsim.utils.objectattributes.attributable.Attributes;

import ch.sbb.matsim.routing.pt.raptor.InitialStop;
import ch.sbb.matsim.routing.pt.raptor.RaptorParameters;
import ch.sbb.matsim.routing.pt.raptor.RaptorStopFinder;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorData;

/**
 * SwissRailRaptor stop finder that also values the long-distance routing surcharge for the request
 * (ADR-0133 D6): it is the first component of a routing request that sees origin, destination and person,
 * so it measures the trip's straight-line distance, lets the valuation price the surcharge for this person
 * and trip, and leaves the result in the {@link LongDistanceSurchargeContext} for the in-vehicle cost.
 * The stops themselves come unchanged from the delegate. A request without coordinates gets the reference
 * surcharge; both counts are logged.
 */
public final class LongDistanceSurchargeStopFinder implements RaptorStopFinder {
	private static final Logger LOGGER = LogManager.getLogger(LongDistanceSurchargeStopFinder.class);
	private static final long LOG_INTERVAL = 1_000_000L;

	private final RaptorStopFinder delegate;
	private final SurchargeValuation valuation;
	private final LongDistanceSurchargeContext context;
	private final AtomicLong valued = new AtomicLong();
	private final AtomicLong withoutCoordinates = new AtomicLong();

	public LongDistanceSurchargeStopFinder(RaptorStopFinder delegate, SurchargeValuation valuation,
			LongDistanceSurchargeContext context) {
		this.delegate = delegate;
		this.valuation = valuation;
		this.context = context;
	}

	@Override
	public List<InitialStop> findStops(Facility fromFacility, Facility toFacility, Person person, double departureTime,
			Attributes routingAttributes, RaptorParameters parameters, SwissRailRaptorData data, Direction type) {
		double seconds;
		long total;
		if (fromFacility != null && toFacility != null && fromFacility.getCoord() != null && toFacility.getCoord() != null) {
			double distanceKm = CoordUtils.calcEuclideanDistance(fromFacility.getCoord(), toFacility.getCoord()) / 1000.0;
			seconds = valuation.surchargeSeconds(person, distanceKm);
			total = valued.incrementAndGet() + withoutCoordinates.get();
		} else {
			seconds = valuation.referenceSurchargeSeconds();
			total = valued.get() + withoutCoordinates.incrementAndGet();
		}
		context.set(person, seconds);
		if (total % LOG_INTERVAL == 0L) {
			LOGGER.info("[vrb-fares] routing surcharge valued for the trip in {}/{} stop searches, reference surcharge in {}",
					valued.get(), total, withoutCoordinates.get());
		}
		return delegate.findStops(fromFacility, toFacility, person, departureTime, routingAttributes, parameters, data, type);
	}
}
