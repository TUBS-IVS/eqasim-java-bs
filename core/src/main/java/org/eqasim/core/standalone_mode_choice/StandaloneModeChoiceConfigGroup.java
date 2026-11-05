package org.eqasim.core.standalone_mode_choice;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import jakarta.validation.constraints.NotNull;
import org.matsim.core.config.ReflectiveConfigGroup;

public class StandaloneModeChoiceConfigGroup extends ReflectiveConfigGroup {

    public static final String GROUP_NAME = "standaloneModeChoice";
    public static final String REMOVE_PERSON_WITH_NO_VALID_ALTERNATIVES = "removePersonsWithNoValidAlternatives";
    public static final String OUTPUT_DIRECTORY = "outputDirectory";
    public static final String CHUNK_SIZE = "chunkSize";
    public static final String EXCLUDED_SUBPOPULATIONS = "excludedSubpopulations";

    private boolean removePersonsWithNoValidAlternative = false;
    private int chunkSize = 1000;
    /**
     * Subpopulations whose persons the standalone mode choice leaves untouched (their selected plan is written as
     * read). Empty by default: every person chooses, as before.
     */
    private Set<String> excludedSubpopulations = Collections.emptySet();

    @NotNull
    private String outputDirectory = "output_mode_choice";

    public StandaloneModeChoiceConfigGroup() {
        super(GROUP_NAME);
    }

    @StringGetter(REMOVE_PERSON_WITH_NO_VALID_ALTERNATIVES)
    public boolean isRemovePersonsWithNoValidAlternative() {
        return this.removePersonsWithNoValidAlternative;
    }

    @StringSetter(REMOVE_PERSON_WITH_NO_VALID_ALTERNATIVES)
    public void setRemovePersonsWithNoValidAlternative(boolean removePersonsWithNoValidAlternative) {
        this.removePersonsWithNoValidAlternative = removePersonsWithNoValidAlternative;
    }

    @StringGetter(OUTPUT_DIRECTORY)
    public String getOutputDirectory() {
        return this.outputDirectory;
    }

    @StringSetter(OUTPUT_DIRECTORY)
    public void setOutputDirectory(String outputDirectory) {
        this.outputDirectory = outputDirectory;
    }
    
    @StringGetter(CHUNK_SIZE)
    public int getChunkSize() {
        return this.chunkSize;
    }

    @StringSetter(CHUNK_SIZE)
    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    /** The excluded subpopulations as a comma-separated list (empty: none excluded). */
    @StringGetter(EXCLUDED_SUBPOPULATIONS)
    public String getExcludedSubpopulations() {
        return String.join(",", this.excludedSubpopulations);
    }

    /** Sets the excluded subpopulations from a comma-separated list; entries are trimmed, empty entries dropped. */
    @StringSetter(EXCLUDED_SUBPOPULATIONS)
    public void setExcludedSubpopulations(String excludedSubpopulations) {
        Set<String> parsed = new LinkedHashSet<>();
        if (excludedSubpopulations != null) {
            for (String entry : excludedSubpopulations.split(",")) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    parsed.add(trimmed);
                }
            }
        }
        this.excludedSubpopulations = Collections.unmodifiableSet(parsed);
    }

    public Set<String> getExcludedSubpopulationSet() {
        return this.excludedSubpopulations;
    }

    @Override
    public Map<String, String> getComments() {
        Map<String, String> comments = super.getComments();
        comments.put(EXCLUDED_SUBPOPULATIONS, "Comma-separated subpopulations whose persons keep their selected plan "
                + "unchanged (no mode choice). Empty: every person chooses. The run fails if the listed "
                + "subpopulations match no person at all.");
        return comments;
    }
}
