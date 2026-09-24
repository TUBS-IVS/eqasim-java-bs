package org.eqasim.braunschweig.fares.zonal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.TransitLine;

/**
 * Tariff scope (regional / long_distance) and mode class per transit line, read from the
 * vrb_line_scopes.csv written by braunschweig.data.vrb.line_scopes (line id = GTFS route id).
 */
public final class PtLineScopes {
	public record Scope(String tariffScope, String modeClass) {
	}

	public static final String SCOPE_REGIONAL = "regional";
	public static final String SCOPE_LONG_DISTANCE = "long_distance";
	static final List<String> HEADER = List.of("line_id", "agency_id", "agency_name", "route_type", "mode_class",
			"tariff_scope");
	private static final Set<String> SCOPES = Set.of(SCOPE_REGIONAL, SCOPE_LONG_DISTANCE);

	private final Map<String, Scope> byLineId;

	private PtLineScopes(Map<String, Scope> byLineId) {
		this.byLineId = Map.copyOf(byLineId);
	}

	public static PtLineScopes of(Map<String, Scope> byLineId) {
		return new PtLineScopes(byLineId);
	}

	public static PtLineScopes read(Path csv) throws IOException {
		List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
		if (lines.isEmpty() || !parseCsvLine(lines.get(0)).equals(HEADER)) {
			throw new IllegalArgumentException(csv + ": expected header " + HEADER);
		}
		Map<String, Scope> result = new HashMap<>();
		for (int index = 1; index < lines.size(); index++) {
			if (lines.get(index).isBlank()) {
				continue;
			}
			List<String> fields = parseCsvLine(lines.get(index));
			if (fields.size() != HEADER.size()) {
				throw new IllegalArgumentException(csv + " line " + (index + 1) + ": expected " + HEADER.size() + " fields");
			}
			String scope = fields.get(5);
			if (!SCOPES.contains(scope)) {
				throw new IllegalArgumentException(csv + " line " + (index + 1) + ": unknown tariff_scope '" + scope + "'");
			}
			if (result.put(fields.get(0), new Scope(scope, fields.get(4))) != null) {
				throw new IllegalArgumentException(csv + " line " + (index + 1) + ": duplicate line_id " + fields.get(0));
			}
		}
		return new PtLineScopes(result);
	}

	public Optional<Scope> scope(Id<TransitLine> lineId) {
		return Optional.ofNullable(byLineId.get(lineId.toString()));
	}

	public int size() {
		return byLineId.size();
	}

	/** Minimal RFC 4180 field splitter: quoted fields may contain commas and doubled quotes. */
	static List<String> parseCsvLine(String line) {
		List<String> fields = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (quoted) {
				if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
					current.append('"');
					i++;
				} else if (c == '"') {
					quoted = false;
				} else {
					current.append(c);
				}
			} else if (c == '"') {
				quoted = true;
			} else if (c == ',') {
				fields.add(current.toString());
				current.setLength(0);
			} else {
				current.append(c);
			}
		}
		fields.add(current.toString());
		return fields;
	}
}
