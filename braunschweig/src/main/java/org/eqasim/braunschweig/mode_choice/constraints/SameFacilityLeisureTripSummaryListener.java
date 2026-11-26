package org.eqasim.braunschweig.mode_choice.constraints;

import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;

import com.google.inject.Inject;

/**
 * Logs the final count of same-facility leisure trips kept on foot or by bicycle when a MATSim run ends
 * (eqasim-bs#679). The running summary lines of {@link SameFacilityLeisureTripConstraint.Factory} appear only when the
 * applied-trip count crosses a power of two; this line gives the final total, also when the rule applied to no trip.
 * It logs nothing when the factory created no constraint (for example when the constraint is not selected).
 *
 * <p>
 * The standalone mode choice ({@code RunStandaloneModeChoice}) fires no controller events, so this listener does not
 * run there; that run shows only the running summary lines.
 */
public class SameFacilityLeisureTripSummaryListener implements ShutdownListener {
	private final SameFacilityLeisureTripConstraint.Factory factory;

	@Inject
	public SameFacilityLeisureTripSummaryListener(SameFacilityLeisureTripConstraint.Factory factory) {
		this.factory = factory;
	}

	@Override
	public void notifyShutdown(ShutdownEvent event) {
		factory.logSummary();
	}
}
