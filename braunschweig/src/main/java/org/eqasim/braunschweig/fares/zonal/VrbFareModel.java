package org.eqasim.braunschweig.fares.zonal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Immutable VRB tariff facts read from vrb_fare_model_2026.json, the contract written by
 * braunschweig.data.vrb.fare_model_export (eqasim-bs). All money in euro cents, distances in
 * kilometres; this class holds no tariff constants of its own (ADR-0133).
 */
public final class VrbFareModel {
	public enum Holder {
		NATIONAL_FLAT, VRB_FLAT, NONE
	}

	public record Band(double upToKm, long priceCents) {
	}

	private final List<String> priceClasses;
	private final Set<String> zones;
	private final Map<String, Long> singleAdultCents;
	private final Map<String, Long> singleChildCents;
	private final Map<String, Long> dayTicketCents;
	private final Map<String, String> priceClassByPair;
	private final Map<String, Holder> holderByCategory;
	private final List<Band> adultBands;
	private final List<Band> childBands;
	private final long shortTripCents;
	private final int shortTripMaximumStopIntervals;
	private final int childMinimumAge;
	private final int childMaximumAge;
	private final double distanceFactor;
	private final long externalLocalSingleCents;
	private final long fallbackCents;

	private VrbFareModel(JsonNode root) {
		if (require(root, "schema_version").asInt() != 1) {
			throw fail("schema_version must be 1");
		}
		priceClasses = strings(require(root, "price_classes"));
		zones = Set.copyOf(strings(require(root, "zones")));
		singleAdultCents = classCents(require(root, "single_adult_cents"), "single_adult_cents");
		singleChildCents = classCents(require(root, "single_child_cents"), "single_child_cents");
		dayTicketCents = classCents(require(root, "day_ticket_cents"), "day_ticket_cents");
		priceClassByPair = new LinkedHashMap<>();
		for (Iterator<Map.Entry<String, JsonNode>> it = require(root, "price_class_by_pair").fields(); it.hasNext();) {
			Map.Entry<String, JsonNode> entry = it.next();
			if (!priceClasses.contains(entry.getValue().asText())) {
				throw fail("price_class_by_pair " + entry.getKey() + " names unknown class " + entry.getValue().asText());
			}
			priceClassByPair.put(entry.getKey(), entry.getValue().asText());
		}
		holderByCategory = new LinkedHashMap<>();
		for (Iterator<Map.Entry<String, JsonNode>> it = require(root, "category_holder").fields(); it.hasNext();) {
			Map.Entry<String, JsonNode> entry = it.next();
			holderByCategory.put(entry.getKey(), Holder.valueOf(entry.getValue().asText().toUpperCase(Locale.ROOT)));
		}
		shortTripCents = nonNegative(require(root, "short_trip_cents").asLong(-1), "short_trip_cents");
		shortTripMaximumStopIntervals = require(root, "short_trip_maximum_stop_intervals").asInt();
		childMinimumAge = require(root, "child_minimum_age").asInt();
		childMaximumAge = require(root, "child_maximum_age").asInt();
		JsonNode external = require(root, "external");
		adultBands = bands(require(external, "rail_distance_bands_adult"));
		childBands = bands(require(external, "rail_distance_bands_child"));
		distanceFactor = require(external, "distance_factor").asDouble();
		if (!(distanceFactor > 0)) {
			throw fail("external.distance_factor must be positive");
		}
		externalLocalSingleCents = nonNegative(require(external, "local_single_cents").asLong(-1), "external.local_single_cents");
		fallbackCents = nonNegative(require(require(root, "fallback"), "unsupported_ride_cents").asLong(-1),
				"fallback.unsupported_ride_cents");
	}

	public static VrbFareModel read(Path path) throws IOException {
		return parse(new ObjectMapper().readTree(path.toFile()));
	}

	public static VrbFareModel parse(JsonNode root) {
		return new VrbFareModel(root);
	}

	/** Matrix price class for a directional zone pair; empty for an undefined cell or an unknown zone. */
	public Optional<String> priceClass(String originZone, String destinationZone) {
		return Optional.ofNullable(priceClassByPair.get(originZone + "|" + destinationZone));
	}

	public long singleCents(String priceClass, boolean child) {
		return (child ? singleChildCents : singleAdultCents).get(priceClass);
	}

	public long dayTicketCents(String priceClass) {
		return dayTicketCents.get(priceClass);
	}

	/** The class with the higher index in price_classes (city < ps1 < ... < ps4). */
	public String higherClass(String a, String b) {
		return priceClasses.indexOf(a) >= priceClasses.indexOf(b) ? a : b;
	}

	/** Holder kind for a population ticket category, or null when the category is not in the model. */
	public Holder holder(String category) {
		return holderByCategory.get(category);
	}

	/** Single fare of the first band whose upper bound covers the tariff distance; empty beyond the last band. */
	public OptionalLong railBandCents(double tariffDistanceKm, boolean child) {
		for (Band band : child ? childBands : adultBands) {
			if (tariffDistanceKm <= band.upToKm()) {
				return OptionalLong.of(band.priceCents());
			}
		}
		return OptionalLong.empty();
	}

	public Set<String> zones() {
		return zones;
	}

	public long shortTripCents() {
		return shortTripCents;
	}

	public int shortTripMaximumStopIntervals() {
		return shortTripMaximumStopIntervals;
	}

	public int childMinimumAge() {
		return childMinimumAge;
	}

	public int childMaximumAge() {
		return childMaximumAge;
	}

	public double distanceFactor() {
		return distanceFactor;
	}

	public long externalLocalSingleCents() {
		return externalLocalSingleCents;
	}

	public long fallbackCents() {
		return fallbackCents;
	}

	private Map<String, Long> classCents(JsonNode node, String name) {
		Map<String, Long> result = new LinkedHashMap<>();
		for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
			Map.Entry<String, JsonNode> entry = it.next();
			if (!priceClasses.contains(entry.getKey())) {
				throw fail(name + " has unknown price class " + entry.getKey());
			}
			result.put(entry.getKey(), nonNegative(entry.getValue().asLong(-1), name + "." + entry.getKey()));
		}
		if (!result.keySet().equals(Set.copyOf(priceClasses))) {
			throw fail(name + " must contain every price class exactly once");
		}
		return result;
	}

	private static List<Band> bands(JsonNode node) {
		List<Band> result = new ArrayList<>();
		double previous = 0.0;
		for (JsonNode item : node) {
			double upTo = require(item, "up_to_km").asDouble();
			if (upTo <= previous) {
				throw fail("rail distance bands must be strictly increasing");
			}
			result.add(new Band(upTo, nonNegative(require(item, "price_cents").asLong(-1), "band.price_cents")));
			previous = upTo;
		}
		if (result.isEmpty()) {
			throw fail("rail distance bands must not be empty");
		}
		return List.copyOf(result);
	}

	private static List<String> strings(JsonNode node) {
		List<String> result = new ArrayList<>();
		node.forEach(item -> result.add(item.asText()));
		if (result.isEmpty()) {
			throw fail("expected a non-empty string array");
		}
		return List.copyOf(result);
	}

	private static JsonNode require(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			throw fail("field '" + field + "' is missing");
		}
		return value;
	}

	private static long nonNegative(long value, String name) {
		if (value < 0) {
			throw fail(name + " must be a non-negative integer");
		}
		return value;
	}

	private static IllegalArgumentException fail(String message) {
		return new IllegalArgumentException("vrb_fare_model: " + message);
	}
}
