package org.eqasim.braunschweig.scenario;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.braunschweig.fares.zonal.VrbZoneFareCostModel;
import org.geotools.api.data.DataStore;
import org.geotools.api.data.DataStoreFinder;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/**
 * Writes the VRB zone id of every stop facility into the attribute {@code vrbTariffZone} (string, from
 * the shapefile attribute {@code zone_id}) and a JSON coverage report (ADR-0133 D2). Facilities
 * outside every polygon keep no attribute; the fare model treats them as external. Unlike the legacy
 * ring tool this never clamps or reinterprets zone ids. Arguments: --input-path, --output-path
 * (transit schedules), --zones-path (shapefile, EPSG:25832 like the schedule), --report-path (JSON).
 */
public class AddVrbTariffZoneInformation {
	private static final Logger LOG = LogManager.getLogger(AddVrbTariffZoneInformation.class);
	static final String ZONE_ID_FIELD = "zone_id";

	public static void main(String[] args) throws ConfigurationException, IOException {
		CommandLine cmd = new CommandLine.Builder(args)
				.requireOptions("input-path", "zones-path", "output-path", "report-path").build();
		TariffZoneAssigner assigner = new TariffZoneAssigner(readZones(new File(cmd.getOptionStrict("zones-path"))));
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new TransitScheduleReader(scenario).readFile(cmd.getOptionStrict("input-path"));
		Map<String, String> modeByFacility = facilityModes(scenario);
		int zoned = 0;
		int ties = 0;
		Map<String, int[]> byMode = new TreeMap<>();
		for (TransitStopFacility facility : scenario.getTransitSchedule().getFacilities().values()) {
			TariffZoneAssigner.Assignment assignment = assigner.assign(facility.getCoord());
			int[] counts = byMode.computeIfAbsent(modeByFacility.getOrDefault(facility.getId().toString(), "unused"),
					key -> new int[2]);
			counts[0]++;
			if (assignment.zoneId() != null) {
				facility.getAttributes().putAttribute(VrbZoneFareCostModel.ZONE_ATTRIBUTE, assignment.zoneId());
				zoned++;
				counts[1]++;
			}
			if (assignment.tie()) {
				ties++;
			}
		}
		int total = scenario.getTransitSchedule().getFacilities().size();
		Files.writeString(Path.of(cmd.getOptionStrict("report-path")), report(total, zoned, ties, byMode),
				StandardCharsets.UTF_8);
		LOG.info(String.format(Locale.ROOT,
				"[vrb-fares] zone attribution: facilities=%d zoned=%d (%.2f%%) unzoned=%d boundary_ties=%d", total, zoned,
				100.0 * zoned / Math.max(total, 1), total - zoned, ties));
		new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(cmd.getOptionStrict("output-path"));
	}

	static String report(int total, int zoned, int ties, Map<String, int[]> byMode) {
		StringBuilder report = new StringBuilder("{\n  \"facilities\": ").append(total).append(",\n  \"zoned\": ")
				.append(zoned).append(",\n  \"unzoned\": ").append(total - zoned).append(",\n  \"boundary_ties\": ")
				.append(ties).append(",\n  \"by_mode\": {");
		String separator = "";
		for (Map.Entry<String, int[]> entry : byMode.entrySet()) {
			report.append(separator).append("\n    \"").append(entry.getKey()).append("\": {\"facilities\": ")
					.append(entry.getValue()[0]).append(", \"zoned\": ").append(entry.getValue()[1]).append("}");
			separator = ",";
		}
		return report.append("\n  }\n}\n").toString();
	}

	static List<TariffZoneAssigner.Zone> readZones(File zonesPath) throws IOException {
		DataStore dataStore = DataStoreFinder.getDataStore(Collections.singletonMap("url", zonesPath.toURI().toURL()));
		if (dataStore == null) {
			throw new IOException("no GeoTools datastore can read " + zonesPath);
		}
		List<TariffZoneAssigner.Zone> zones = new ArrayList<>();
		try {
			SimpleFeatureSource source = dataStore.getFeatureSource(dataStore.getTypeNames()[0]);
			try (SimpleFeatureIterator iterator = source.getFeatures().features()) {
				while (iterator.hasNext()) {
					SimpleFeature feature = iterator.next();
					Object zoneId = feature.getAttribute(ZONE_ID_FIELD);
					if (zoneId == null) {
						throw new IOException(zonesPath + ": feature without " + ZONE_ID_FIELD + " attribute");
					}
					zones.add(new TariffZoneAssigner.Zone(zoneId.toString(), (Geometry) feature.getDefaultGeometry()));
				}
			}
		} finally {
			dataStore.dispose();
		}
		if (zones.isEmpty()) {
			throw new IOException(zonesPath + ": no zone features");
		}
		return zones;
	}

	/** Transport mode of the first transit route using a facility (report grouping only). */
	private static Map<String, String> facilityModes(Scenario scenario) {
		Map<String, String> result = new TreeMap<>();
		for (TransitLine line : scenario.getTransitSchedule().getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				for (TransitRouteStop stop : route.getStops()) {
					result.putIfAbsent(stop.getStopFacility().getId().toString(), route.getTransportMode());
				}
			}
		}
		return result;
	}
}
