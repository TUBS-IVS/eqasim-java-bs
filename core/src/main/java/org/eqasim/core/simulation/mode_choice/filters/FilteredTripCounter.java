package org.eqasim.core.simulation.mode_choice.filters;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;

/**
 * Counts the trips the mode choice handles, those it does not re-choose because a filter rejects them
 * ({@link KeepFilteredTripsModel}, eqasim-bs#678), and, for the kept trips that are routed again
 * ({@link KeptTripElements.Rerouted}), how many fell back to walk and how many kept an existing walk-fallback stamp. In
 * the MATSim loop it logs one line per iteration after the replanning and starts over; the standalone mode choice
 * reads it once after its pass.
 */
public class FilteredTripCounter implements BeforeMobsimListener {
	private static final Logger logger = LogManager.getLogger(FilteredTripCounter.class);

	private final AtomicLong numberOfTrips = new AtomicLong();
	private final AtomicLong numberOfKeptTrips = new AtomicLong();
	private final AtomicLong numberOfReroutedTrips = new AtomicLong();
	private final AtomicLong numberOfWalkFallbacks = new AtomicLong();
	private final AtomicLong numberOfKeptStamps = new AtomicLong();

	public void add(long trips, long keptTrips) {
		numberOfTrips.addAndGet(trips);
		numberOfKeptTrips.addAndGet(keptTrips);
	}

	/** One kept trip routed again: did it fall back to walk now, did it keep an existing stamp. */
	public void addRerouted(long walkFallbacks, long keptStamps) {
		numberOfReroutedTrips.incrementAndGet();
		numberOfWalkFallbacks.addAndGet(walkFallbacks);
		numberOfKeptStamps.addAndGet(keptStamps);
	}

	public long getNumberOfTrips() {
		return numberOfTrips.get();
	}

	public long getNumberOfKeptTrips() {
		return numberOfKeptTrips.get();
	}

	public long getNumberOfReroutedTrips() {
		return numberOfReroutedTrips.get();
	}

	public long getNumberOfWalkFallbacks() {
		return numberOfWalkFallbacks.get();
	}

	public long getNumberOfKeptStamps() {
		return numberOfKeptStamps.get();
	}

	/**
	 * "X of Y trips (p %) are rejected by the model's filter and not re-chosen", then what happened to them: kept
	 * unchanged, or routed again with eqasim's routing and how many of those fell back to walk or kept an existing
	 * stamp. Counted over every plan the model has chosen for so far; a person without a valid alternative (the model
	 * throws) is not counted.
	 */
	public String summary() {
		long trips = numberOfTrips.get();
		long kept = numberOfKeptTrips.get();
		long rerouted = numberOfReroutedTrips.get();
		String summary = String.format(Locale.ROOT,
				"%d of %d trips (%.2f %%) are rejected by the model's filter and not re-chosen", kept, trips,
				percent(kept, trips));

		if (rerouted > 0) {
			long fallbacks = numberOfWalkFallbacks.get();
			return summary + String.format(Locale.ROOT,
					"; %d of them were routed again with eqasim's routing: %d fell back to walk (%.2f %%), "
							+ "%d kept an existing walk-fallback stamp",
					rerouted, fallbacks, percent(fallbacks, rerouted), numberOfKeptStamps.get());
		} else if (kept > 0) {
			return summary + "; they were kept unchanged";
		}
		return summary;
	}

	private static double percent(long part, long total) {
		return total == 0 ? 0.0 : 100.0 * part / total;
	}

	@Override
	public void notifyBeforeMobsim(BeforeMobsimEvent event) {
		// replanning of this iteration is done; iteration 0 has none
		if (numberOfTrips.get() > 0) {
			logger.info(String.format(Locale.ROOT, "Mode choice replanning of iteration %d: %s", event.getIteration(),
					summary()));
		}
		numberOfTrips.set(0);
		numberOfKeptTrips.set(0);
		numberOfReroutedTrips.set(0);
		numberOfWalkFallbacks.set(0);
		numberOfKeptStamps.set(0);
	}
}
