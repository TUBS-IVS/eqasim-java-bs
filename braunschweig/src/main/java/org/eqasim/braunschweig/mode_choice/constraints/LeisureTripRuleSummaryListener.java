package org.eqasim.braunschweig.mode_choice.constraints;

import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;

import com.google.inject.Inject;

/**
 * Logs the final counts of the two leisure trip rules of eqasim-bs#679 when a MATSim run ends: the same-facility
 * leisure trips and the trips into an out-and-back stroll, both kept on foot or by bicycle. The running summary lines of {@link SameFacilityLeisureTripConstraint.Factory} appear only when the
 * applied-trip count crosses a power of two; this line gives the final total, also when the rule applied to no trip.
 * It logs nothing when the factory created no constraint (for example when the constraint is not selected).
 *
 * <p>
 * The standalone mode choice ({@code RunStandaloneModeChoice}) fires no controller events, so this listener does not
 * run there; that run shows only the running summary lines.
 *
 * <p>
 * The second line comes from {@link StrollTripConstraint.Factory} and follows the same rules.
 */
public class LeisureTripRuleSummaryListener implements ShutdownListener {
	private final SameFacilityLeisureTripConstraint.Factory factory;
	private final StrollTripConstraint.Factory strollFactory;

	@Inject
	public LeisureTripRuleSummaryListener(SameFacilityLeisureTripConstraint.Factory factory,
			StrollTripConstraint.Factory strollFactory) {
		this.factory = factory;
		this.strollFactory = strollFactory;
	}

	@Override
	public void notifyShutdown(ShutdownEvent event) {
		factory.logSummary();
		strollFactory.logSummary();
	}
}
