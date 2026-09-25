package org.eqasim.braunschweig.fares.zonal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.TransitLine;

public class PtLineScopesTest {
	@Test
	public void readsQuotedCsvAndAnswersByLineId() throws Exception {
		Path csv = Files.createTempFile("scopes", ".csv");
		Files.writeString(csv, "line_id,agency_id,agency_name,route_type,mode_class,tariff_scope\n"
				+ "re-2,dbr,\"DB Regio AG, Region Nord\",106,rail,regional\n"
				+ "ice-1,dbf,DB Fernverkehr AG,101,rail,long_distance\n");
		PtLineScopes scopes = PtLineScopes.read(csv);
		assertEquals(new PtLineScopes.Scope("regional", "rail"), scopes.scope(Id.create("re-2", TransitLine.class)).orElseThrow());
		assertEquals("long_distance", scopes.scope(Id.create("ice-1", TransitLine.class)).orElseThrow().tariffScope());
		assertFalse(scopes.scope(Id.create("unknown", TransitLine.class)).isPresent());
		assertEquals(2, scopes.size());
	}

	@Test
	public void rejectsWrongHeaderAndUnknownScopeValues() throws Exception {
		Path wrongHeader = Files.createTempFile("scopes", ".csv");
		Files.writeString(wrongHeader, "line,scope\nx,regional\n");
		assertThrows(IllegalArgumentException.class, () -> PtLineScopes.read(wrongHeader));
		Path badScope = Files.createTempFile("scopes", ".csv");
		Files.writeString(badScope, "line_id,agency_id,agency_name,route_type,mode_class,tariff_scope\nx,a,A,3,bus,elsewhere\n");
		assertThrows(IllegalArgumentException.class, () -> PtLineScopes.read(badScope));
	}
}
