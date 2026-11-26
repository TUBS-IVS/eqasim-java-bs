package org.eqasim.core.simulation.mode_choice.filters;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;

/**
 * Counts the trips the mode choice handles and those it keeps unchanged because a filter rejects them
 * ({@link KeepFilteredTripsModel}, eqasim-bs#678). In the MATSim loop it logs one line per iteration after the
 * replanning and starts over; the standalone mode choice reads it once after its pass.
 */
public class FilteredTripCounter implements BeforeMobsimListener {
	private static final Logger logger = LogManager.getLogger(FilteredTripCounter.class);

	private final AtomicLong numberOfTrips = new AtomicLong();
	private final AtomicLong numberOfKeptTrips = new AtomicLong();

	public void add(long trips, long keptTrips) {
		numberOfTrips.addAndGet(trips);
		numberOfKeptTrips.addAndGet(keptTrips);
	}

	public long getNumberOfTrips() {
		return numberOfTrips.get();
	}

	public long getNumberOfKeptTrips() {
		return numberOfKeptTrips.get();
	}

	/**
	 * "X of Y trips (p %) are rejected by the model's filter and not re-chosen", counted over every plan the model
	 * has chosen for so far; a person without a valid alternative (the model throws) is not counted.
	 */
	public String summary() {
		long trips = numberOfTrips.get();
		long kept = numberOfKeptTrips.get();
		return String.format(Locale.ROOT,
				"%d of %d trips (%.2f %%) are rejected by the model's filter and not re-chosen", kept,
				trips, trips == 0 ? 0.0 : 100.0 * kept / trips);
	}

	@Override
	public void notifyBeforeMobsim(BeforeMobsimEvent event) {
		// replanning of this iteration is done; iteration 0 has none
		if (numberOfTrips.get() > 0) {
			logger.info(String.format(Locale.ROOT,
					"Mode choice replanning of iteration %d: %s; they were routed again with eqasim's routing",
					event.getIteration(), summary()));
		}
		numberOfTrips.set(0);
		numberOfKeptTrips.set(0);
	}
}
