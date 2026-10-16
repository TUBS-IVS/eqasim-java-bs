package org.eqasim.braunschweig.simwrapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;

import com.google.inject.Inject;

/**
 * Makes the households input of the run configuration resolvable inside the output directory, so that MATSim's
 * SimWrapper public transit analysis can load its scenario at the end of the run.
 *
 * <p>
 * Root cause: {@code PublicTransitAnalysis} loads the scenario from {@code output_config.xml}. That file keeps the
 * input paths of the run configuration, which are relative and are resolved against the output directory by the
 * analysis. The analysis re-points the network, the transit schedule and vehicles to the {@code output_*} files and
 * clears plans and facilities, but it does not touch the households input. MATSim only writes
 * {@code output_households.xml.gz}, so the configured households file name does not exist in the output directory
 * and {@code ScenarioUtils.loadScenario} fails with a {@code FileNotFoundException}.
 *
 * <p>
 * Fix (our side only, no library code is changed): after MATSim has written its outputs and before the SimWrapper
 * analyses run, a relative link under the configured households file name is created in the output directory that
 * points to {@code output_households.xml.gz}. The households content is the one the run actually used. A relative
 * symbolic link is preferred (no duplicate of a large file, output directory stays relocatable); if the file system
 * refuses it, the file is copied instead and the log says so. Nothing that already exists under the configured name is
 * ever overwritten.
 *
 * <p>
 * Ordering: listeners are called in descending priority at shutdown. MATSim's {@code DumpDataAtEnd} (0.0) writes the
 * households file, the {@code SimWrapperListener} (-1000.0) runs the analyses; this listener runs in between.
 */
public class SimWrapperHouseholdsInputLinker implements ShutdownListener {
	private static final Logger log = LogManager.getLogger(SimWrapperHouseholdsInputLinker.class);

	/** Called after DumpDataAtEnd (0.0) and before the SimWrapperListener (-1000.0). */
	public static final double PRIORITY = -500.0;

	/** Outcome of one attempt to make the households input resolvable in the output directory. */
	public enum Outcome {
		/** The run configuration has no households input; the analysis loads none. */
		NOT_CONFIGURED,
		/** The configured path is absolute or a URL; the analysis resolves it without the output directory. */
		NOT_RELATIVE,
		/** The configured relative path would leave the output directory; nothing is created. */
		OUTSIDE_OUTPUT_DIRECTORY,
		/** A file already exists under the configured name; it is left untouched. */
		ALREADY_PRESENT,
		/** MATSim did not write its households file; the analysis will fail and says so itself. */
		SOURCE_MISSING,
		/** A relative symbolic link was created. */
		LINKED,
		/** The symbolic link was refused and the file was copied instead. */
		COPIED,
		/** Neither link nor copy could be created; the error was logged. */
		FAILED
	}

	/** Creates a symbolic link; the seam that lets tests exercise the copy fallback. */
	@FunctionalInterface
	interface SymbolicLinkCreator {
		void create(Path link, Path target) throws IOException;
	}

	private final Config config;
	private final OutputDirectoryHierarchy outputDirectory;

	@Inject
	public SimWrapperHouseholdsInputLinker(Config config, OutputDirectoryHierarchy outputDirectory) {
		this.config = config;
		this.outputDirectory = outputDirectory;
	}

	@Override
	public double priority() {
		return PRIORITY;
	}

	@Override
	public void notifyShutdown(ShutdownEvent event) {
		if (event.isUnexpected()) {
			log.warn("Run terminated unexpectedly, not linking the households input into the output directory");
			return;
		}

		Path producedFile = Paths.get(outputDirectory.getOutputFilename(Controler.DefaultFiles.households,
				config.controller().getCompressionType()));
		ensureHouseholdsInput(Paths.get(outputDirectory.getOutputPath()), config.households().getInputFile(),
				producedFile);
	}

	/**
	 * Makes {@code configuredHouseholdsFile} (the households input of the run configuration, resolved by the analysis
	 * against the output directory) point to {@code producedHouseholdsFile} (MATSim's households output).
	 *
	 * @return what was done; every outcome is logged, none is silent
	 */
	public static Outcome ensureHouseholdsInput(Path outputDirectory, String configuredHouseholdsFile,
			Path producedHouseholdsFile) {
		return ensureHouseholdsInput(outputDirectory, configuredHouseholdsFile, producedHouseholdsFile,
				Files::createSymbolicLink);
	}

	static Outcome ensureHouseholdsInput(Path outputDirectory, String configuredHouseholdsFile,
			Path producedHouseholdsFile, SymbolicLinkCreator linkCreator) {
		if (configuredHouseholdsFile == null || configuredHouseholdsFile.isBlank()) {
			log.info("[simwrapper] no households input configured, nothing to link for the SimWrapper analyses");
			return Outcome.NOT_CONFIGURED;
		}

		if (configuredHouseholdsFile.contains("://") || configuredHouseholdsFile.startsWith("file:")
				|| Paths.get(configuredHouseholdsFile).isAbsolute()) {
			log.info("[simwrapper] households input " + configuredHouseholdsFile + " is not relative to the output "
					+ "directory, the SimWrapper analyses resolve it directly; not linking");
			return Outcome.NOT_RELATIVE;
		}

		Path normalizedOutputDirectory = outputDirectory.toAbsolutePath().normalize();
		Path link = normalizedOutputDirectory.resolve(configuredHouseholdsFile).normalize();

		if (!link.startsWith(normalizedOutputDirectory)) {
			log.warn("[simwrapper] households input " + configuredHouseholdsFile + " resolves to " + link
					+ " outside the output directory " + normalizedOutputDirectory + "; not linking, the "
					+ "SimWrapper public transit analysis will not find the households file");
			return Outcome.OUTSIDE_OUTPUT_DIRECTORY;
		}

		if (Files.exists(link)) {
			log.info("[simwrapper] " + link + " already exists, leaving it untouched");
			return Outcome.ALREADY_PRESENT;
		}

		Path source = producedHouseholdsFile.toAbsolutePath().normalize();

		if (!Files.exists(source)) {
			log.warn("[simwrapper] MATSim did not write " + source + "; the SimWrapper public transit analysis "
					+ "resolves the configured households input " + configuredHouseholdsFile + " against "
					+ normalizedOutputDirectory + " and will fail to find it");
			return Outcome.SOURCE_MISSING;
		}

		try {
			Files.createDirectories(link.getParent());

			if (Files.isSymbolicLink(link)) {
				// A dangling link of an earlier run in the same directory (Files.exists is false for it).
				Files.delete(link);
				log.warn("[simwrapper] removed dangling symbolic link " + link);
			}
		} catch (IOException e) {
			log.error("[simwrapper] could not prepare " + link + " for the households input of the SimWrapper "
					+ "analyses: " + e, e);
			return Outcome.FAILED;
		}

		try {
			// Relative target: the output directory stays valid when it is moved or archived as a whole.
			linkCreator.create(link, link.getParent().relativize(source));
			log.info("[simwrapper] households input for the SimWrapper analyses: linked " + link + " -> "
					+ link.getParent().relativize(source) + " (the SimWrapper public transit analysis resolves the "
					+ "configured households input against the output directory)");
			return Outcome.LINKED;
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			log.warn("[simwrapper] symbolic link " + link + " refused (" + e + "), copying " + source + " instead");
		}

		try {
			Files.copy(source, link, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
			log.info("[simwrapper] households input for the SimWrapper analyses: copied " + source + " to " + link);
			return Outcome.COPIED;
		} catch (IOException e) {
			log.error("[simwrapper] could neither link nor copy " + source + " to " + link + ": " + e
					+ "; the SimWrapper public transit analysis will fail to find the households file", e);
			return Outcome.FAILED;
		}
	}
}
