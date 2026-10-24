package org.eqasim.core.scenario.cutter.population.trips;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Counts how NetworkTripProcessor labels the inside pieces of network trips
 * (car, car_passenger, ...) that cross the scenario border (eqasim-bs#465).
 *
 * An inside piece either contains the main network leg, and then keeps the
 * trip's routing mode, or lies entirely within one access or egress leg, and
 * then keeps that leg's mode (a short walk to or from the border). The
 * "corrected" count is the number of pieces of the first kind whose crossing
 * leg is an access or egress leg: before the fix, these pieces took the walk
 * mode of that leg, so a car trip from a home just outside the border became a
 * walk across the whole inside part (107 km for one in-commuter of the 1 % run).
 *
 * Thread-safe; one instance is shared by all cutter threads.
 */
public class NetworkTripCrossingStatistics {
	private static final Logger logger = LogManager.getLogger(NetworkTripCrossingStatistics.class);

	private final AtomicLong routingModePieces = new AtomicLong();
	private final AtomicLong correctedPieces = new AtomicLong();
	private final AtomicLong accessEgressOnlyPieces = new AtomicLong();

	void recordRoutingModePiece(boolean crossingLegModeDiffers) {
		routingModePieces.incrementAndGet();

		if (crossingLegModeDiffers) {
			correctedPieces.incrementAndGet();
		}
	}

	void recordAccessEgressOnlyPiece() {
		accessEgressOnlyPieces.incrementAndGet();
	}

	public long getRoutingModePieces() {
		return routingModePieces.get();
	}

	public long getCorrectedPieces() {
		return correctedPieces.get();
	}

	public long getAccessEgressOnlyPieces() {
		return accessEgressOnlyPieces.get();
	}

	public void log() {
		long routing = routingModePieces.get();
		long corrected = correctedPieces.get();
		long accessEgress = accessEgressOnlyPieces.get();
		long total = routing + accessEgress;

		logger.info(String.format(Locale.ROOT,
				"[cutter] inside pieces of border-crossing network trips: %d; with the trip routing mode %d (%.1f%%), "
						+ "of which %d cross the border on an access/egress leg (labelled with that leg's mode before "
						+ "eqasim-bs#465); within a single access/egress leg %d (%.1f%%)",
				total, routing, share(routing, total), corrected, accessEgress, share(accessEgress, total)));
	}

	private static double share(long count, long total) {
		return total == 0 ? 0.0 : 100.0 * count / total;
	}
}
