package org.eqasim.braunschweig.mode_choice;

import org.eqasim.braunschweig.fares.zonal.LongDistanceSurchargeContext;
import org.eqasim.braunschweig.fares.zonal.LongDistanceSurchargeStopFinder;
import org.eqasim.braunschweig.fares.zonal.VrbFareModel;
import org.eqasim.braunschweig.mode_choice.parameters.BraunschweigModeParameters;

import com.google.inject.Inject;
import com.google.inject.Provider;

import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorStopFinder;
import ch.sbb.matsim.routing.pt.raptor.RaptorStopFinder;

/** Wraps SwissRailRaptor's default stop finder with the per-trip valuation of the long-distance surcharge. */
public final class VrbLongDistanceStopFinderProvider implements Provider<RaptorStopFinder> {
	private final DefaultRaptorStopFinder delegate;
	private final VrbFareModel fareModel;
	private final BraunschweigModeParameters parameters;
	private final LongDistanceSurchargeContext context;

	@Inject
	public VrbLongDistanceStopFinderProvider(DefaultRaptorStopFinder delegate, VrbFareModel fareModel,
			BraunschweigModeParameters parameters, LongDistanceSurchargeContext context) {
		this.delegate = delegate;
		this.fareModel = fareModel;
		this.parameters = parameters;
		this.context = context;
	}

	@Override
	public RaptorStopFinder get() {
		return new LongDistanceSurchargeStopFinder(delegate,
				new ModeChoiceValueOfTime(fareModel.longDistanceSingleCents(), parameters), context);
	}
}
