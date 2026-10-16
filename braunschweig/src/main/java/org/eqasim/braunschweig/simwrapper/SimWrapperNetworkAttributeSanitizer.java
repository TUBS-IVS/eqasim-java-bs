package org.eqasim.braunschweig.simwrapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.network.NetworkUtils;
import org.matsim.utils.objectattributes.attributable.Attributable;

import com.google.inject.Inject;

/**
 * Provides SimWrapper with a copy of the MATSim output network whose link and node attribute names are legal Avro
 * names, so that its network export no longer fails.
 *
 * <p>
 * Root cause: the SimWrapper {@code OverviewDashboard} runs {@code CreateAvroNetwork --with-properties} (hard-coded in
 * the dashboard, not configurable). The command turns every link and node attribute name into an Avro field name, and
 * Avro only accepts names matching {@code [A-Za-z_][A-Za-z0-9_]*}. Networks converted from OSM by pt2matsim carry
 * attributes such as {@code osm:way:highway} and {@code osm:way:id}, so the command throws a
 * {@code SchemaParseException} and the network map of the overview dashboard is not created. The command resolves its
 * input network with {@code ApplicationUtils.matchInput("network.xml", <output directory>)}, which prefers a file named
 * {@code network.xml.gz} over MATSim's {@code output_network.xml.gz}.
 *
 * <p>
 * Fix (our side only, no library code is changed): after MATSim has written its outputs and before the SimWrapper
 * analyses run, the output network is read, every illegal attribute name is replaced by a legal one (illegal
 * characters become {@code _}, a leading digit gets a {@code _} prefix, collisions get a numeric suffix), and the
 * result is written as {@code network.xml.gz} into the output directory. Values, geometry, modes, capacities and the
 * network CRS are copied unchanged; only the attribute names differ.
 *
 * <p>
 * Trade-off: the SimWrapper analyses (network export, traffic and public transit analyses) read the renamed copy, so
 * the network map shows {@code osm_way_highway} instead of {@code osm:way:highway}. The MATSim output network is never
 * modified, because other tools read the original names from it. The copy stays in the output directory (a re-run of
 * the SimWrapper analyses from the command line finds it as well) and costs about the size of the output network. A
 * network without illegal names is not copied. Every renamed name and the number of affected elements is logged.
 *
 * <p>
 * Ordering: listeners are called in descending priority at shutdown. MATSim's {@code DumpDataAtEnd} (0.0) writes the
 * output network, the {@code SimWrapperListener} (-1000.0) runs the analyses; this listener runs in between.
 */
public class SimWrapperNetworkAttributeSanitizer implements ShutdownListener {
	private static final Logger log = LogManager.getLogger(SimWrapperNetworkAttributeSanitizer.class);

	/** Called after DumpDataAtEnd (0.0) and before the SimWrapperListener (-1000.0). */
	public static final double PRIORITY = -400.0;

	/**
	 * File name in the output directory that the SimWrapper lookup {@code matchInput("network.xml", dir)} prefers over
	 * {@code output_network.xml.gz}; verified against the real lookup in the unit tests.
	 */
	public static final String SANITIZED_NETWORK_FILE_NAME = "network.xml.gz";

	/** Outcome of one sanitizing attempt. */
	public enum Outcome {
		/** All link and node attribute names are legal Avro names; nothing was written. */
		NOTHING_TO_RENAME,
		/** The sanitized copy was written. */
		WRITTEN,
		/** The MATSim output network does not exist; nothing was written. */
		SOURCE_MISSING
	}

	/**
	 * Result of one sanitizing attempt.
	 *
	 * @param renamedLinkAttributes original to new name of every renamed link attribute
	 * @param renamedNodeAttributes original to new name of every renamed node attribute
	 * @param affectedLinks         number of links that carry at least one renamed attribute
	 * @param affectedNodes         number of nodes that carry at least one renamed attribute
	 */
	public record Result(Outcome outcome, Map<String, String> renamedLinkAttributes,
			Map<String, String> renamedNodeAttributes, int affectedLinks, int affectedNodes) {
	}

	private final Config config;
	private final OutputDirectoryHierarchy outputDirectory;

	@Inject
	public SimWrapperNetworkAttributeSanitizer(Config config, OutputDirectoryHierarchy outputDirectory) {
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
			log.warn("Run terminated unexpectedly, not preparing a SimWrapper network copy");
			return;
		}

		Path source = Paths.get(outputDirectory.getOutputFilename(Controler.DefaultFiles.network,
				config.controller().getCompressionType()));
		Path target = Paths.get(outputDirectory.getOutputPath()).resolve(SANITIZED_NETWORK_FILE_NAME);

		try {
			sanitize(source, target);
		} catch (RuntimeException e) {
			// The copy only repairs the input of a non-fatal analysis; the analysis then reports its own error.
			log.error("[simwrapper] could not prepare the SimWrapper network copy " + target + " from " + source
					+ "; the SimWrapper network export will use the original network: " + e, e);
		}
	}

	/** Replaces every character outside [A-Za-z0-9_] by an underscore and prefixes a leading digit. */
	public static String sanitizeName(String name) {
		StringBuilder builder = new StringBuilder(name.length() + 1);

		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			builder.append(isLegalCharacter(c) ? c : '_');
		}

		if (builder.length() == 0 || Character.isDigit(builder.charAt(0))) {
			builder.insert(0, '_');
		}

		return builder.toString();
	}

	private static boolean isLegalCharacter(char c) {
		return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
	}

	private static boolean isLegalName(String name) {
		return !name.isEmpty() && sanitizeName(name).equals(name);
	}

	/**
	 * Reads sourceNetwork and, if any link or node attribute has an illegal Avro name, writes a copy with legal names
	 * to targetNetwork, replacing an existing file. sourceNetwork is never modified.
	 *
	 * <p>
	 * The copy is first written to a temporary file next to the target and moved into place, so an interrupted write
	 * can never leave a truncated network under the name the SimWrapper lookup prefers.
	 */
	public static Result sanitize(Path sourceNetwork, Path targetNetwork) {
		if (!Files.exists(sourceNetwork)) {
			log.warn("[simwrapper] MATSim output network " + sourceNetwork + " does not exist, no SimWrapper network "
					+ "copy is written");
			return new Result(Outcome.SOURCE_MISSING, Map.of(), Map.of(), 0, 0);
		}

		Network network = NetworkUtils.readNetwork(sourceNetwork.toString());

		Map<String, String> linkMapping = createMapping(network.getLinks().values());
		Map<String, String> nodeMapping = createMapping(network.getNodes().values());

		if (linkMapping.isEmpty() && nodeMapping.isEmpty()) {
			log.info("[simwrapper] all " + network.getLinks().size() + " link and " + network.getNodes().size()
					+ " node attribute sets of " + sourceNetwork + " use legal Avro names, no network copy needed");
			return new Result(Outcome.NOTHING_TO_RENAME, Map.of(), Map.of(), 0, 0);
		}

		int affectedLinks = rename(network.getLinks().values(), linkMapping);
		int affectedNodes = rename(network.getNodes().values(), nodeMapping);

		Path temporary = targetNetwork.resolveSibling(targetNetwork.getFileName() + ".partial.gz");
		boolean replacesExistingFile = Files.exists(targetNetwork);

		try {
			NetworkUtils.writeNetwork(network, temporary.toString());
			Files.move(temporary, targetNetwork, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | RuntimeException e) {
			try {
				Files.deleteIfExists(temporary);
			} catch (IOException suppressed) {
				e.addSuppressed(suppressed);
			}

			if (e instanceof IOException) {
				throw new UncheckedIOException("Could not write the sanitized network " + targetNetwork, (IOException) e);
			}
			throw (RuntimeException) e;
		}

		if (replacesExistingFile) {
			log.warn("[simwrapper] replaced the existing file " + targetNetwork + " (stale copy of an earlier run?)");
		}

		log.info("[simwrapper] SimWrapper's network export rejects attribute names that are not legal Avro names; wrote "
				+ targetNetwork + " from " + sourceNetwork + " with " + linkMapping.size() + " link attribute name(s) "
				+ "renamed on " + affectedLinks + "/" + network.getLinks().size() + " links and " + nodeMapping.size()
				+ " node attribute name(s) renamed on " + affectedNodes + "/" + network.getNodes().size()
				+ " nodes; values are unchanged, " + sourceNetwork + " is not modified");
		log.info("[simwrapper] renamed link attributes (original -> new): " + linkMapping);
		log.info("[simwrapper] renamed node attributes (original -> new): " + nodeMapping);

		return new Result(Outcome.WRITTEN, linkMapping, nodeMapping, affectedLinks, affectedNodes);
	}

	/**
	 * Assigns a legal, unique replacement to every illegal attribute name found on the elements. The mapping is global
	 * per element kind because CreateAvroNetwork builds one column per name across all links (and across all nodes).
	 * Names are processed in sorted order, so the result is deterministic.
	 */
	private static Map<String, String> createMapping(Collection<? extends Attributable> elements) {
		Set<String> names = new TreeSet<>();

		for (Attributable element : elements) {
			names.addAll(element.getAttributes().getAsMap().keySet());
		}

		Set<String> used = new HashSet<>();
		List<String> illegal = new ArrayList<>();

		for (String name : names) {
			if (isLegalName(name)) {
				used.add(name);
			} else {
				illegal.add(name);
			}
		}

		Map<String, String> mapping = new TreeMap<>();

		for (String name : illegal) {
			String base = sanitizeName(name);
			String candidate = base;

			for (int suffix = 2; used.contains(candidate); suffix++) {
				candidate = base + "_" + suffix;
			}

			used.add(candidate);
			mapping.put(name, candidate);
		}

		return mapping;
	}

	/** Renames the attributes in place; returns the number of elements that carried at least one renamed name. */
	private static int rename(Collection<? extends Attributable> elements, Map<String, String> mapping) {
		int affected = 0;

		if (mapping.isEmpty()) {
			return affected;
		}

		for (Attributable element : elements) {
			boolean isAffected = false;

			for (Map.Entry<String, Object> entry : new ArrayList<>(element.getAttributes().getAsMap().entrySet())) {
				String replacement = mapping.get(entry.getKey());

				if (replacement != null) {
					element.getAttributes().removeAttribute(entry.getKey());
					element.getAttributes().putAttribute(replacement, entry.getValue());
					isAffected = true;
				}
			}

			if (isAffected) {
				affected++;
			}
		}

		return affected;
	}
}
