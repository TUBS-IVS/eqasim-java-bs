package org.eqasim.braunschweig.scenario;

import org.matsim.api.core.v01.Coord;

/**
 * One off-street parking lot of the eqasim-bs release parking_lots_osm_2026 (issue #465, ADR-0169).
 *
 * @param lotId  OSM identity, e.g. {@code way/9251051}
 * @param coord  centroid of the lot in the network CRS (EPSG:25832, metres)
 * @param spaces estimated parking spaces, at least 1
 */
public record ParkingLot(String lotId, Coord coord, int spaces) {
}
