package org.eqasim.braunschweig.parking;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Immutable parking tariff model read from the JSON contract of design "parking cost zones", section 5.4, written by the
 * eqasim-bs preparation stage: schema 2 (parking cost zones v2, eqasim-bs issue #436: garage and commuter products, the
 * parking search time, the resident permit flag and the list of resident districts) and, unchanged, schema 1 of version
 * 1. Money in euro cents, durations in minutes, fee windows in seconds after midnight of the simulated weekday; the class
 * holds no tariff constants of its own.
 *
 * <p>The reader is strict so that a contract drift fails at read time instead of silently changing prices: the schema
 * version must be one of {@link #SUPPORTED_SCHEMA_VERSIONS} (checked first, as it decides the other fields), the currency
 * EUR, the terminal-stay rule until_fee_end and weekday_only true (the only semantics ParkingCostCalculator implements);
 * every document, source, district and zone object must carry exactly the keys of the declared schema version (a nullable
 * field is written as JSON null, never omitted); money, minutes and seconds must be integral JSON numbers (never a fraction
 * that would be truncated, never a string); an unknown zone type or a zone that violates the rules of {@link ZoneTariff}
 * (aligned with the Python reference that writes the model) fails. Error messages name the zone id and the field.
 *
 * <p>A schema-1 model prices exactly as under version 1: its zones have no garage, no commuter product and no search
 * time, and carry the default of their zone type for the permit flag (ASSUMPTION R2-a: true for a street or resident
 * zone, false for a campus), which only rule R2 reads; a schema-1 model lists no resident district, so a plan that names
 * one does not match it.
 */
public final class ParkingTariffs {
	private static final Logger LOG = LogManager.getLogger(ParkingTariffs.class);

	/** The newest JSON contract version this reader accepts: the one the current preparation stage writes. */
	public static final int SCHEMA_VERSION = 2;

	/** Every JSON contract version this reader accepts: schema 2 is schema 1 plus optional fields and the district list. */
	public static final List<Integer> SUPPORTED_SCHEMA_VERSIONS = List.of(1, 2);

	/** Currency of every amount in the model; amounts are integer cents of it. */
	public static final String CURRENCY = "EUR";

	/**
	 * One input file of the tariff release, recorded for traceability.
	 *
	 * @param sourceId stable id of the input (e.g. the tariff table or the zone polygons)
	 * @param path path of the input as recorded by the exporter
	 * @param sha256 SHA-256 of the input content, 64 lowercase hex characters
	 */
	public record Source(String sourceId, String path, String sha256) {
	}

	/**
	 * One resident parking district of the release (spec Amendment C3), a value of the plan attributes parkingDistrict
	 * and residentParkingDistrict.
	 *
	 * @param districtId district id as used in the plan attributes
	 * @param municipalityAgs 8-digit AGS of the municipality that owns the district
	 */
	public record ResidentDistrict(String districtId, String municipalityAgs) {
	}

	private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

	private static final Pattern MUNICIPALITY_AGS = Pattern.compile("[0-9]{8}");

	private static final List<String> SCHEMA_1_DOCUMENT_FIELDS = List.of("schema_version", "tariff_snapshot_date", "currency",
			"terminal_stay_rule", "weekday_only", "assumptions", "sources", "zones");

	/** Schema 2 adds the list of resident districts (spec Amendment C3) to the document. */
	private static final List<String> SCHEMA_2_DOCUMENT_FIELDS = List.of("schema_version", "tariff_snapshot_date", "currency",
			"terminal_stay_rule", "weekday_only", "assumptions", "sources", "resident_districts", "zones");

	private static final List<String> SOURCE_FIELDS = List.of("source_id", "path", "sha256");

	private static final List<String> RESIDENT_DISTRICT_FIELDS = List.of("district_id", "municipality_ags");

	private static final List<String> SCHEMA_1_ZONE_FIELDS = List.of("zone_type", "hourly_rate_cents", "billing_unit_min",
			"free_if_stay_at_most_min", "first_period_min", "first_period_cents", "daily_cap_cents", "max_stay_min",
			"long_stay_product_cents", "member_day_cents", "guest_day_cents", "fee_start_s", "fee_end_s", "resident_exempt");

	/**
	 * Schema 2 adds, to the fields of schema 1, the garage product, the commuter product, the parking search time and the
	 * resident permit flag, in the order of the Python reference's ZoneTariff.
	 */
	private static final List<String> SCHEMA_2_ZONE_FIELDS = concat(SCHEMA_1_ZONE_FIELDS,
			List.of("garage_hourly_rate_cents", "garage_billing_unit_min", "garage_first_period_min",
					"garage_first_period_cents", "garage_daily_cap_cents", "garage_fee_start_s", "garage_fee_end_s",
					"commuter_day_cents", "search_time_min", "resident_permits_valid"));

	private final int schemaVersion;
	private final LocalDate tariffSnapshotDate;
	private final String terminalStayRule;
	private final List<String> assumptions;
	private final List<Source> sources;
	private final List<ResidentDistrict> residentDistricts;
	private final SortedMap<String, ResidentDistrict> residentDistrictsById;
	private final SortedMap<String, ZoneTariff> zonesById;

	private ParkingTariffs(JsonNode root) {
		if (root == null || !root.isObject()) {
			throw fail("the tariff model must be a JSON object");
		}
		// The version decides which fields the document has, so it is checked before them: a document of another schema
		// then fails with its version instead of with the fields that schema renamed or added.
		schemaVersion = integralInt(root.get("schema_version"), "schema_version");
		if (!SUPPORTED_SCHEMA_VERSIONS.contains(schemaVersion)) {
			throw fail("schema_version must be one of " + SUPPORTED_SCHEMA_VERSIONS + ", got " + schemaVersion);
		}
		requireExactFields(root, schemaVersion == 1 ? SCHEMA_1_DOCUMENT_FIELDS : SCHEMA_2_DOCUMENT_FIELDS, "the tariff model",
				schemaVersion);
		tariffSnapshotDate = isoDate(root.get("tariff_snapshot_date"), "tariff_snapshot_date");
		String currency = text(root.get("currency"), "currency");
		if (!CURRENCY.equals(currency)) {
			throw fail("currency must be " + CURRENCY + " (every amount is in euro cents), got " + currency);
		}
		terminalStayRule = text(root.get("terminal_stay_rule"), "terminal_stay_rule");
		if (!ParkingCostCalculator.TERMINAL_STAY_RULE_UNTIL_FEE_END.equals(terminalStayRule)) {
			throw fail("terminal_stay_rule must be " + ParkingCostCalculator.TERMINAL_STAY_RULE_UNTIL_FEE_END
					+ " (the only rule the cost calculator implements), got " + terminalStayRule);
		}
		JsonNode weekdayOnly = root.get("weekday_only");
		if (!weekdayOnly.isBoolean() || !weekdayOnly.booleanValue()) {
			throw fail("weekday_only must be true: the cost calculator prices one average weekday (assumption D1), got "
					+ weekdayOnly);
		}
		assumptions = strings(root.get("assumptions"), "assumptions");
		sources = readSources(root.get("sources"), schemaVersion);
		// A schema-1 model has no district list: no district exists for it, as under version 1.
		residentDistricts = schemaVersion == 1 ? List.of() : readResidentDistricts(root.get("resident_districts"), schemaVersion);
		TreeMap<String, ResidentDistrict> districtsById = new TreeMap<>();
		for (ResidentDistrict district : residentDistricts) {
			districtsById.put(district.districtId(), district);
		}
		residentDistrictsById = Collections.unmodifiableSortedMap(districtsById);
		zonesById = zones(root.get("zones"), schemaVersion);
	}

	/**
	 * Reads and validates a tariff model file. Duplicate keys (for example a zone id listed twice) and trailing content
	 * (for example a second document appended to the file) fail, instead of the last duplicate silently replacing a
	 * tariff or the rest of the file being ignored. Logs one line with the path, the zone counts per type, the snapshot
	 * date, the schema version, the number of resident districts and the number of sources.
	 *
	 * @throws IOException for an unreadable file or malformed JSON, including duplicate keys
	 * @throws IllegalArgumentException for a document that violates the contract; the message names the file
	 */
	public static ParkingTariffs read(Path path) throws IOException {
		JsonNode root = new ObjectMapper()
				.enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.readTree(path.toFile());
		ParkingTariffs tariffs;
		try {
			tariffs = parse(root);
		} catch (IllegalArgumentException error) {
			// The message already names the zone and field; add the file so that the release can be found.
			throw new IllegalArgumentException(path + ": " + error.getMessage(), error);
		}
		LOG.info("[parking] tariff model {}: {} zones {}, {} resident district(s), snapshot {}, schema {}, {} source(s)", path,
				tariffs.zonesById.size(), tariffs.zoneCountsByType(), tariffs.residentDistricts.size(),
				tariffs.tariffSnapshotDate, tariffs.schemaVersion, tariffs.sources.size());
		return tariffs;
	}

	/** Validates an already parsed document; see the class comment for the rules. */
	public static ParkingTariffs parse(JsonNode root) {
		return new ParkingTariffs(root);
	}

	/**
	 * One zone object of the {@code zones} map of the given schema, which for schema 2 is also the shape of the
	 * {@code tariffs} block of the golden-case fixture: exactly the fields of design section 5.4 of that schema, JSON null
	 * for "not applicable". A schema-1 zone gets no garage, no commuter product, no search time and the permit flag of its
	 * zone type ({@link ZoneTariff.ZoneType#defaultResidentPermitsValid}).
	 */
	static ZoneTariff parseZone(String zoneId, JsonNode zone, int schemaVersion) {
		if (!SUPPORTED_SCHEMA_VERSIONS.contains(schemaVersion)) {
			throw fail("schema_version must be one of " + SUPPORTED_SCHEMA_VERSIONS + ", got " + schemaVersion);
		}
		String context = "zone " + zoneId;
		if (zone == null || !zone.isObject()) {
			throw fail(context + " must be a JSON object, got " + zone);
		}
		boolean schemaTwo = schemaVersion == 2;
		requireExactFields(zone, schemaTwo ? SCHEMA_2_ZONE_FIELDS : SCHEMA_1_ZONE_FIELDS, context, schemaVersion);
		String typeName = text(zone.get("zone_type"), context + ": zone_type");
		ZoneTariff.ZoneType zoneType = ZoneTariff.ZoneType.fromJsonName(typeName)
				.orElseThrow(() -> fail(context + ": unknown zone_type " + typeName + "; expected one of "
						+ Arrays.stream(ZoneTariff.ZoneType.values()).map(ZoneTariff.ZoneType::jsonName).toList()));
		return new ZoneTariff(zoneId, zoneType, optionalCents(zone, "hourly_rate_cents", context),
				optionalMinutes(zone, "billing_unit_min", context), optionalMinutes(zone, "free_if_stay_at_most_min", context),
				optionalMinutes(zone, "first_period_min", context), optionalCents(zone, "first_period_cents", context),
				optionalCents(zone, "daily_cap_cents", context), optionalMinutes(zone, "max_stay_min", context),
				optionalCents(zone, "long_stay_product_cents", context), optionalCents(zone, "member_day_cents", context),
				optionalCents(zone, "guest_day_cents", context), integralInt(zone.get("fee_start_s"), context + ": fee_start_s"),
				integralInt(zone.get("fee_end_s"), context + ": fee_end_s"),
				literalBoolean(zone.get("resident_exempt"), context + ": resident_exempt"),
				schemaTwo ? optionalCents(zone, "garage_hourly_rate_cents", context) : OptionalLong.empty(),
				schemaTwo ? optionalMinutes(zone, "garage_billing_unit_min", context) : OptionalInt.empty(),
				schemaTwo ? optionalMinutes(zone, "garage_first_period_min", context) : OptionalInt.empty(),
				schemaTwo ? optionalCents(zone, "garage_first_period_cents", context) : OptionalLong.empty(),
				schemaTwo ? optionalCents(zone, "garage_daily_cap_cents", context) : OptionalLong.empty(),
				schemaTwo ? optionalSeconds(zone, "garage_fee_start_s", context) : OptionalInt.empty(),
				schemaTwo ? optionalSeconds(zone, "garage_fee_end_s", context) : OptionalInt.empty(),
				schemaTwo ? optionalCents(zone, "commuter_day_cents", context) : OptionalLong.empty(),
				schemaTwo ? optionalMinutes(zone, "search_time_min", context) : OptionalInt.empty(),
				schemaTwo ? literalBoolean(zone.get("resident_permits_valid"), context + ": resident_permits_valid")
						: zoneType.defaultResidentPermitsValid());
	}

	/** Tariff of a zone, or empty for an id the model does not contain; the caller decides how to fail. */
	public Optional<ZoneTariff> zone(String zoneId) {
		Objects.requireNonNull(zoneId, "zoneId");
		return Optional.ofNullable(zonesById.get(zoneId));
	}

	/** All zone ids, sorted and unmodifiable. */
	public Set<String> zones() {
		return zonesById.keySet();
	}

	/** The JSON contract version the model declares: 1 or 2. */
	public int schemaVersion() {
		return schemaVersion;
	}

	/** Date of the tariff snapshot the model was built from. */
	public LocalDate tariffSnapshotDate() {
		return tariffSnapshotDate;
	}

	/** Terminal-stay rule the model was built for; always until_fee_end. */
	public String terminalStayRule() {
		return terminalStayRule;
	}

	/** Assumption statements recorded by the exporter, unmodifiable. */
	public List<String> assumptions() {
		return assumptions;
	}

	/** Input files of the release with their content hashes, unmodifiable. */
	public List<Source> sources() {
		return sources;
	}

	/**
	 * The resident parking districts of the release in the order of the model (sorted by id by the exporter),
	 * unmodifiable; empty for a schema-1 model and for a model built without a district layer.
	 */
	public List<ResidentDistrict> residentDistricts() {
		return residentDistricts;
	}

	/** The resident district with this id, or empty for an id the model does not list; the caller decides how to fail. */
	public Optional<ResidentDistrict> residentDistrict(String districtId) {
		Objects.requireNonNull(districtId, "districtId");
		return Optional.ofNullable(residentDistrictsById.get(districtId));
	}

	private Map<String, Integer> zoneCountsByType() {
		Map<String, Integer> counts = new TreeMap<>();
		for (ZoneTariff tariff : zonesById.values()) {
			counts.merge(tariff.zoneType().jsonName(), 1, Integer::sum);
		}
		return counts;
	}

	private static SortedMap<String, ZoneTariff> zones(JsonNode node, int schemaVersion) {
		if (!node.isObject() || node.isEmpty()) {
			throw fail("zones must be a non-empty object of zone id to tariff, got " + node);
		}
		SortedMap<String, ZoneTariff> zones = new TreeMap<>();
		for (Map.Entry<String, JsonNode> entry : node.properties()) {
			zones.put(entry.getKey(), parseZone(entry.getKey(), entry.getValue(), schemaVersion));
		}
		return Collections.unmodifiableSortedMap(zones);
	}

	private static List<Source> readSources(JsonNode node, int schemaVersion) {
		if (!node.isArray() || node.isEmpty()) {
			throw fail("sources must be a non-empty array: a tariff model without its inputs is not traceable");
		}
		List<Source> result = new ArrayList<>();
		for (JsonNode item : node) {
			if (!item.isObject()) {
				throw fail("every sources entry must be a JSON object, got " + item);
			}
			requireExactFields(item, SOURCE_FIELDS, "sources entry", schemaVersion);
			String sha256 = text(item.get("sha256"), "sources.sha256");
			if (!SHA256_HEX.matcher(sha256).matches()) {
				throw fail("sources.sha256 must be 64 lowercase hex characters, got " + sha256);
			}
			result.add(new Source(text(item.get("source_id"), "sources.source_id"), text(item.get("path"), "sources.path"),
					sha256));
		}
		return List.copyOf(result);
	}

	/**
	 * The district list of a schema-2 model (spec Amendment C3): an array, empty for a release without a district layer,
	 * of objects with exactly a district id (text without surrounding whitespace) and the 8-digit AGS of the municipality
	 * that owns the district; ids are unique, so that a membership test against the list is unambiguous.
	 */
	private static List<ResidentDistrict> readResidentDistricts(JsonNode node, int schemaVersion) {
		if (!node.isArray()) {
			throw fail("resident_districts must be an array of districts, got " + node);
		}
		List<ResidentDistrict> result = new ArrayList<>();
		Set<String> seen = new TreeSet<>();
		for (JsonNode item : node) {
			if (!item.isObject()) {
				throw fail("resident_districts entry must be a JSON object, got " + item);
			}
			requireExactFields(item, RESIDENT_DISTRICT_FIELDS, "resident_districts entry", schemaVersion);
			String districtId = text(item.get("district_id"), "resident_districts.district_id");
			if (!districtId.equals(districtId.strip())) {
				throw fail("resident_districts.district_id must be a text without surrounding whitespace, got '" + districtId
						+ "'");
			}
			JsonNode ags = item.get("municipality_ags");
			if (!ags.isTextual() || !MUNICIPALITY_AGS.matcher(ags.textValue()).matches()) {
				throw fail("resident_districts.municipality_ags of district " + districtId + " must be an 8-digit text, got "
						+ ags);
			}
			if (!seen.add(districtId)) {
				throw fail("duplicate district_id " + districtId + " in resident_districts");
			}
			result.add(new ResidentDistrict(districtId, ags.textValue()));
		}
		return List.copyOf(result);
	}

	private static List<String> strings(JsonNode node, String name) {
		if (!node.isArray() || node.isEmpty()) {
			throw fail(name + " must be a non-empty array of strings, got " + node);
		}
		List<String> result = new ArrayList<>();
		for (JsonNode item : node) {
			result.add(text(item, name + " entry"));
		}
		return List.copyOf(result);
	}

	/** Fails with the missing and the unknown keys, so a renamed or dropped field is not read as null. */
	private static void requireExactFields(JsonNode node, List<String> expected, String context, int schemaVersion) {
		Set<String> present = new TreeSet<>();
		for (Map.Entry<String, JsonNode> entry : node.properties()) {
			present.add(entry.getKey());
		}
		Set<String> missing = new TreeSet<>(expected);
		missing.removeAll(present);
		Set<String> unknown = new TreeSet<>(present);
		unknown.removeAll(expected);
		if (!missing.isEmpty() || !unknown.isEmpty()) {
			throw fail(context + " must have exactly the schema-" + schemaVersion + " fields; missing " + missing
					+ ", unknown " + unknown);
		}
	}

	/** Nullable money: JSON null is empty, otherwise an integral JSON number of euro cents. */
	private static OptionalLong optionalCents(JsonNode zone, String field, String context) {
		JsonNode value = zone.get(field);
		if (value.isNull()) {
			return OptionalLong.empty();
		}
		if (!value.isIntegralNumber() || !value.canConvertToLong()) {
			throw fail(context + ": " + field + " must be an integral number of euro cents or null, got " + value);
		}
		return OptionalLong.of(value.longValue());
	}

	/** Nullable duration: JSON null is empty, otherwise an integral JSON number of minutes. */
	private static OptionalInt optionalMinutes(JsonNode zone, String field, String context) {
		JsonNode value = zone.get(field);
		if (value.isNull()) {
			return OptionalInt.empty();
		}
		if (!value.isIntegralNumber() || !value.canConvertToInt()) {
			throw fail(context + ": " + field + " must be an integral number of minutes or null, got " + value);
		}
		return OptionalInt.of(value.intValue());
	}

	/** Nullable time of day: JSON null is empty, otherwise an integral JSON number of seconds after midnight. */
	private static OptionalInt optionalSeconds(JsonNode zone, String field, String context) {
		JsonNode value = zone.get(field);
		if (value.isNull()) {
			return OptionalInt.empty();
		}
		if (!value.isIntegralNumber() || !value.canConvertToInt()) {
			throw fail(context + ": " + field + " must be an integral number of seconds or null, got " + value);
		}
		return OptionalInt.of(value.intValue());
	}

	/** A version or a time in seconds: an integral JSON number, never a fraction or a string. */
	private static int integralInt(JsonNode value, String name) {
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
			throw fail(name + " must be an integral number, got " + value);
		}
		return value.intValue();
	}

	private static boolean literalBoolean(JsonNode value, String name) {
		if (value == null || !value.isBoolean()) {
			throw fail(name + " must be a JSON boolean, got " + value);
		}
		return value.booleanValue();
	}

	private static String text(JsonNode value, String name) {
		if (value == null || !value.isTextual() || value.textValue().isBlank()) {
			throw fail(name + " must be a non-blank string, got " + value);
		}
		return value.textValue();
	}

	private static LocalDate isoDate(JsonNode value, String name) {
		String date = text(value, name);
		try {
			return LocalDate.parse(date);
		} catch (DateTimeParseException error) {
			throw new IllegalArgumentException("parking_tariffs: " + name + " must be an ISO date (yyyy-mm-dd), got " + date,
					error);
		}
	}

	private static List<String> concat(List<String> first, List<String> second) {
		List<String> result = new ArrayList<>(first);
		result.addAll(second);
		return List.copyOf(result);
	}

	private static IllegalArgumentException fail(String message) {
		return new IllegalArgumentException("parking_tariffs: " + message);
	}
}
