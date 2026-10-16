package org.eqasim.core.simulation.analysis;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Copies a table of the last iteration (ITERS/it.N) to the output root at controller shutdown.
 *
 * Downstream analyses read the output-root files, so the copy must neither fail silently nor leave a stale file of a
 * previous run in place: an existing output file is replaced (and the replacement is logged), and any I/O failure is
 * logged with the file pair and rethrown as an unchecked exception.
 */
public final class AnalysisOutputFileCopier {
	private final static Logger log = LogManager.getLogger(AnalysisOutputFileCopier.class);

	private AnalysisOutputFileCopier() {
	}

	/**
	 * Copies iterationFile to outputFile, replacing outputFile if it already exists.
	 *
	 * @throws UncheckedIOException if the copy fails, e.g. because the iteration file does not exist
	 */
	public static void copyToOutputRoot(File iterationFile, File outputFile) {
		boolean replacesExistingFile = outputFile.exists();

		try {
			Files.copy(iterationFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			String message = "Could not copy eqasim analysis file " + iterationFile + " to " + outputFile + ": " + e;
			log.error(message);
			throw new UncheckedIOException(message, e);
		}

		if (replacesExistingFile) {
			log.info("Replaced existing output file " + outputFile + " with " + iterationFile);
		}
	}
}
