package org.eqasim.braunschweig.mode_choice.constraints;

import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;

import com.google.inject.Inject;

/**
 * Logs the final rate of border-tour vehicle legs relaxed for missing availability when a MATSim run ends
 * (eqasim-bs#678). The running summary lines of {@link BorderTourVehicleConstraint.Factory} appear only when the
 * relaxed count crosses a power of two; this line gives the final total, also when nothing was relaxed. It logs nothing
 * when the constraint saw no border-tour vehicle leg (for example when it is not selected).
 *
 * <p>
 * The standalone mode choice ({@code RunStandaloneModeChoice}) fires no controller events, so this listener does not
 * run there; that run shows only the running summary lines.
 */
public class BorderTourVehicleSummaryListener implements ShutdownListener {
	private final BorderTourVehicleConstraint.Factory factory;

	@Inject
	public BorderTourVehicleSummaryListener(BorderTourVehicleConstraint.Factory factory) {
		this.factory = factory;
	}

	@Override
	public void notifyShutdown(ShutdownEvent event) {
		factory.logSummary();
	}
}
