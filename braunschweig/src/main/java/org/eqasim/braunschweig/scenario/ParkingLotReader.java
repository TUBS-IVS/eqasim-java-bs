package org.eqasim.braunschweig.scenario;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.matsim.api.core.v01.Coord;

/**
 * Reads the parking-lot release of eqasim-bs (data record parking_lots_osm_2026, written by
 * {@code scripts/build_parking_lots_from_osm.py}): a semicolon-separated file with at least the columns
 * {@code lot_id}, {@code x}, {@code y} (EPSG:25832, metres) and {@code spaces}; further columns are ignored. A field
 * may be double-quoted (pandas quotes text that holds the separator, e.g. {@code "private;customers"}); {@code ""}
 * inside quotes is one quote character.
 *
 * <p>
 * Fails with an {@link IllegalStateException} naming the file and line on a missing column, an empty id, a
 * non-finite coordinate, spaces that are not a whole number of at least 1, a duplicate id or a file without rows:
 * a wrong lot would silently move part of a site's car access to the wrong road. Side effect: reads the file.
 * </p>
 */
public final class ParkingLotReader {
	private static final List<String> REQUIRED_COLUMNS = List.of("lot_id", "x", "y", "spaces");

	private ParkingLotReader() {
	}

	public static List<ParkingLot> read(Path path) {
		List<String> lines;
		try {
			lines = Files.readAllLines(path, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("could not read the parking-lot file " + path, e);
		}
		if (lines.isEmpty()) {
			throw new IllegalStateException("parking-lot file " + path + " is empty");
		}
		List<String> header = splitFields(lines.get(0).trim());
		int[] columns = new int[REQUIRED_COLUMNS.size()];
		for (int index = 0; index < REQUIRED_COLUMNS.size(); index++) {
			columns[index] = header.indexOf(REQUIRED_COLUMNS.get(index));
			if (columns[index] < 0) {
				throw new IllegalStateException("parking-lot file " + path + " lacks the column '" + REQUIRED_COLUMNS.get(index)
						+ "' (header: " + lines.get(0).trim() + ")");
			}
		}

		List<ParkingLot> lots = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		for (int lineIndex = 1; lineIndex < lines.size(); lineIndex++) {
			String line = lines.get(lineIndex);
			if (line.isBlank()) {
				continue;
			}
			List<String> fields = splitFields(line);
			String where = path + " line " + (lineIndex + 1);
			if (fields.size() != header.size()) {
				throw new IllegalStateException(where + ": expected " + header.size() + " fields, got " + fields.size());
			}
			String lotId = fields.get(columns[0]).trim();
			if (lotId.isEmpty()) {
				throw new IllegalStateException(where + ": empty lot_id");
			}
			double x = parseCoordinate(fields.get(columns[1]), "x", where);
			double y = parseCoordinate(fields.get(columns[2]), "y", where);
			int spaces;
			try {
				spaces = Integer.parseInt(fields.get(columns[3]).trim());
			} catch (NumberFormatException e) {
				throw new IllegalStateException(where + ": spaces '" + fields.get(columns[3]) + "' is not a whole number");
			}
			if (spaces < 1) {
				throw new IllegalStateException(where + ": spaces must be at least 1, got " + spaces);
			}
			if (!ids.add(lotId)) {
				throw new IllegalStateException(where + ": duplicate lot_id " + lotId);
			}
			lots.add(new ParkingLot(lotId, new Coord(x, y), spaces));
		}
		if (lots.isEmpty()) {
			throw new IllegalStateException("parking-lot file " + path + " has no lot rows");
		}
		return lots;
	}

	/** The semicolon-separated fields of one line; double-quoted fields may hold separators and doubled quotes. */
	static List<String> splitFields(String line) {
		List<String> fields = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean quoted = false;
		for (int index = 0; index < line.length(); index++) {
			char character = line.charAt(index);
			if (quoted) {
				if (character == '"') {
					if (index + 1 < line.length() && line.charAt(index + 1) == '"') {
						field.append('"');
						index++;
					} else {
						quoted = false;
					}
				} else {
					field.append(character);
				}
			} else if (character == '"') {
				quoted = true;
			} else if (character == ';') {
				fields.add(field.toString());
				field.setLength(0);
			} else {
				field.append(character);
			}
		}
		fields.add(field.toString());
		return fields;
	}

	private static double parseCoordinate(String text, String column, String where) {
		double value;
		try {
			value = Double.parseDouble(text.trim());
		} catch (NumberFormatException e) {
			throw new IllegalStateException(where + ": " + column + " '" + text + "' is not a number");
		}
		if (!Double.isFinite(value)) {
			throw new IllegalStateException(where + ": " + column + " must be finite, got " + text);
		}
		return value;
	}
}
