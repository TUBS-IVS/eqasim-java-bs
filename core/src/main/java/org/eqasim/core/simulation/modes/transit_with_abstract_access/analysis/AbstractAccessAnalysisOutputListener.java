package org.eqasim.core.simulation.modes.transit_with_abstract_access.analysis;

import com.google.inject.Inject;
import org.eqasim.core.components.config.EqasimConfigGroup;
import org.eqasim.core.simulation.analysis.AnalysisOutputFileCopier;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.controler.listener.ShutdownListener;

import java.io.File;
import java.io.IOException;

public class AbstractAccessAnalysisOutputListener implements IterationStartsListener, IterationEndsListener, ShutdownListener {
    /** File name of the abstract access legs table in the output root. */
    public static final String ABSTRACT_ACCESS_LEGS_FILE_NAME = "eqasim_abstract_access_legs.csv";
    /** Per-iteration abstract access legs file; must not end with "legs.csv" (MATSim matchInput suffix collision, see AnalysisOutputListener.ITERATION_TRIPS_FILE_NAME). */
    public static final String ITERATION_ABSTRACT_ACCESS_LEGS_FILE_NAME = "eqasim_abstract_access_legs_iteration.csv";

    private final OutputDirectoryHierarchy outputDirectoryHierarchy;

    private final AbstractAccessLegListener abstractAccessLegListener;

    private final int analysisInterval;

    private boolean isAnalysisActive;

    @Inject
    public AbstractAccessAnalysisOutputListener(EqasimConfigGroup eqasimConfigGroup, OutputDirectoryHierarchy outputDirectoryHierarchy, AbstractAccessLegListener abstractAccessLegListener) {
        this.outputDirectoryHierarchy = outputDirectoryHierarchy;
        this.abstractAccessLegListener = abstractAccessLegListener;
        this.analysisInterval = eqasimConfigGroup.getAnalysisInterval();
    }


    @Override
    public void notifyIterationStarts(IterationStartsEvent event) {
        this.isAnalysisActive = false;
        if(analysisInterval > 0) {
            if(event.getIteration() % this.analysisInterval ==  0 || event.isLastIteration()) {
                this.isAnalysisActive = true;
                event.getServices().getEvents().addHandler(this.abstractAccessLegListener);
            }
        }
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        if(isAnalysisActive) {
            event.getServices().getEvents().removeHandler(this.abstractAccessLegListener);
            try {
                new AbstractAccessLegWriter(this.abstractAccessLegListener.getAbstractAccessLegItems(), ";")
                        .write(outputDirectoryHierarchy.getIterationFilename(event.getIteration(), ITERATION_ABSTRACT_ACCESS_LEGS_FILE_NAME));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }


    @Override
    public void notifyShutdown(ShutdownEvent event) {
        AnalysisOutputFileCopier.copyToOutputRoot(
                new File(this.outputDirectoryHierarchy.getIterationFilename(event.getIteration(), ITERATION_ABSTRACT_ACCESS_LEGS_FILE_NAME)),
                new File(outputDirectoryHierarchy.getOutputFilename(ABSTRACT_ACCESS_LEGS_FILE_NAME)));
    }
}
