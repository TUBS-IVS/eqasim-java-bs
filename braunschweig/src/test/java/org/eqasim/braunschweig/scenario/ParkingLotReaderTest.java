package org.eqasim.braunschweig.scenario;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The parking-lot release of eqasim-bs (data record parking_lots_osm_2026) as the large-site access reads it: a lot
 * with a wrong number of spaces or a broken coordinate would shift a site's car access to the wrong road, so every
 * defect fails loudly with the file and line.
 */
public class ParkingLotReaderTest {
	private static final String HEADER = "lot_id;x;y;spaces;capacity_source;access;parking;name";

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	private Path write(String... lines) throws Exception {
		Path path = temporaryFolder.newFile().toPath();
		Files.writeString(path, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
		return path;
	}

	@Test
	public void readsTheReleaseColumns() throws Exception {
		List<ParkingLot> lots = ParkingLotReader.read(write(HEADER,
				"node/6;600.00;10.00;12;tag;customers;surface;",
				"way/1;25.00;25.00;100;area;private;surface;VW WOB MA P6"));
		assertEquals(2, lots.size());
		assertEquals("node/6", lots.get(0).lotId());
		assertEquals(600.0, lots.get(0).coord().getX(), 0.0);
		assertEquals(10.0, lots.get(0).coord().getY(), 0.0);
		assertEquals(12, lots.get(0).spaces());
		assertEquals(100, lots.get(1).spaces());
	}

	@Test
	public void quotedFieldsWithSemicolonsAreOneField() throws Exception {
		// pandas quotes a text field that holds the separator: "private;customers" is one access value.
		List<ParkingLot> lots = ParkingLotReader.read(write(HEADER,
				"\"way/9\";1.00;2.00;3;area;\"private;customers\";surface;\"Werk \"\"Nord\"\"; P1\""));
		assertEquals(1, lots.size());
		assertEquals("way/9", lots.get(0).lotId());
		assertEquals(3, lots.get(0).spaces());
		assertEquals(List.of("a", "b;c", "d\"e", ""), ParkingLotReader.splitFields("a;\"b;c\";\"d\"\"e\";"));
	}

	@Test
	public void aMissingColumnIsRejected() throws Exception {
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> ParkingLotReader.read(write("lot_id;x;y", "way/1;1;2")));
		assertTrue(error.getMessage(), error.getMessage().contains("spaces"));
	}

	@Test
	public void invalidRowsAreRejectedWithTheirLine() throws Exception {
		for (String row : List.of("way/2;NaN;5;10;area;;;", "way/2;1;5;0;area;;;", "way/2;1;5;1.5;area;;;",
				"way/2;1;5;-3;area;;;", ";1;5;3;area;;;")) {
			IllegalStateException error = assertThrows(IllegalStateException.class,
					() -> ParkingLotReader.read(write(HEADER, "way/1;0;0;5;area;;;", row)));
			assertTrue(row + ": " + error.getMessage(), error.getMessage().contains("line 3"));
		}
	}

	@Test
	public void duplicateIdsAndEmptyFilesAreRejected() throws Exception {
		IllegalStateException duplicate = assertThrows(IllegalStateException.class,
				() -> ParkingLotReader.read(write(HEADER, "way/1;0;0;5;area;;;", "way/1;9;9;5;area;;;")));
		assertTrue(duplicate.getMessage(), duplicate.getMessage().contains("way/1"));
		assertThrows(IllegalStateException.class, () -> ParkingLotReader.read(write(HEADER)));
	}
}
