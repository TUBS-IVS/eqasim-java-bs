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
 * Immutable parking tariff model read from the JSON contract of design "parking cost zones", section 5.4 (schema 1),
 * written by the eqasim-bs preparation stage. Money in euro cents, durations in minutes, fee windows in seconds after
 * midnight of the simulated weekday; the class holds no tariff constants of its own.
 *
 * <p>The reader is strict so that a contract drift fails at read time instead of silently changing prices: the schema
 * version must be 1, the currency EUR, the terminal-stay rule until_fee_end and weekday_only true (the only semantics
 * ParkingCostCalculator implements); every document, source and zone object must carry exactly its schema-1 keys (a
 * nullable field is written as JSON null, never omitted); money, minutes and seconds must be integral JSON numbers
 * (never a fraction that would be truncated, never a string); an unknown zone type or a zone that violates the
 * per-type rules of {@link ZoneTariff} fails. Error messages name the zone id and the field.
 */
public final class ParkingTariffs {
	private static final Logger LOG = LogManager.getLogger(ParkingTariffs.class);

	/** The only JSON contract version this reader accepts. */
	public static final int SCHEMA_VERSION = 1;

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

	private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

	private static final List<String> DOCUMENT_FIELDS = List.of("schema_version", "tariff_snapshot_date", "currency",
			"terminal_stay_rule", "weekday_only", "assumptions", "sources", "zones");

	private static final List<String> SOURCE_FIELDS = List.of("source_id", "path", "sha256");

	private static final List<String> ZONE_FIELDS = List.of("zone_type", "hourly_rate_cents", "billing_unit_min",
			"free_if_stay_at_most_min", "first_period_min", "first_period_cents", "daily_cap_cents", "max_stay_min",
			"long_stay_product_cents", "member_day_cents", "guest_day_cents", "fee_start_s", "fee_end_s", "resident_exempt");

	private final LocalDate tariffSnapshotDate;
	private final String terminalStayRule;
	private final List<String> assumptions;
	private final List<Source> sources;
	private final SortedMap<String, ZoneTariff> zonesById;

	private ParkingTariffs(JsonNode root) {
		if (root == null || !root.isObject()) {
			throw fail("the tariff model must be a JSON object");
		}
		requireExactFields(root, DOCUMENT_FIELDS, "the tariff model");
		int schemaVersion = integralInt(root.get("schema_version"), "schema_version");
		if (schemaVersion != SCHEMA_VERSION) {
			throw fail("schema_version must be " + SCHEMA_VERSION + ", got " + schemaVersion);
		}
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
		sources = sources(root.get("sources"));
		zonesById = zones(root.get("zones"));
	}

	/**
	 * Reads and validates a tariff model file. Duplicate keys (for example a zone id listed twice) and trailing content
	 * fail, instead of the last duplicate silently replacing a tariff. Logs one line with the zone counts per type, the
	 * snapshot date and the number of sources.
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
		LOG.info("[parking] tariff model {}: {} zones {}, snapshot {}, schema {}, {} source(s)", path,
				tariffs.zonesById.size(), tariffs.zoneCountsByType(), tariffs.tariffSnapshotDate, SCHEMA_VERSION,
				tariffs.sources.size());
		return tariffs;
	}

	/** Validates an already parsed document; see the class comment for the rules. */
	public static ParkingTariffs parse(JsonNode root) {
		return new ParkingTariffs(root);
	}

	/**
	 * One zone object of the schema-1 {@code zones} map, which is also the shape of the {@code tariffs} block of the
	 * golden-case fixture: exactly the fourteen fields of design section 5.4, JSON null for "not applicable".
	 */
	static ZoneTariff parseZone(String zoneId, JsonNode zone) {
		String context = "zone " + zoneId;
		if (zone == null || !zone.isObject()) {
			throw fail(context + " must be a JSON object, got " + zone);
		}
		requireExactFields(zone, ZONE_FIELDS, context);
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
				literalBoolean(zone.get("resident_exempt"), context + ": resident_exempt"));
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

	/** Date of the tariff snapshot the model was built from. */
	public LocalDate tariffSnapshotDate() {
		return tariffSnapshotDate;
	}

	/** Terminal-stay rule the model was built for; always until_fee_end in schema 1. */
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

	private Map<String, Integer> zoneCountsByType() {
		Map<String, Integer> counts = new TreeMap<>();
		for (ZoneTariff tariff : zonesById.values()) {
			counts.merge(tariff.zoneType().jsonName(), 1, Integer::sum);
		}
		return counts;
	}

	private static SortedMap<String, ZoneTariff> zones(JsonNode node) {
		if (!node.isObject() || node.isEmpty()) {
			throw fail("zones must be a non-empty object of zone id to tariff, got " + node);
		}
		SortedMap<String, ZoneTariff> zones = new TreeMap<>();
		for (Map.Entry<String, JsonNode> entry : node.properties()) {
			zones.put(entry.getKey(), parseZone(entry.getKey(), entry.getValue()));
		}
		return Collections.unmodifiableSortedMap(zones);
	}

	private static List<Source> sources(JsonNode node) {
		if (!node.isArray() || node.isEmpty()) {
			throw fail("sources must be a non-empty array: a tariff model without its inputs is not traceable");
		}
		List<Source> result = new ArrayList<>();
		for (JsonNode item : node) {
			if (!item.isObject()) {
				throw fail("every sources entry must be a JSON object, got " + item);
			}
			requireExactFields(item, SOURCE_FIELDS, "sources entry");
			String sha256 = text(item.get("sha256"), "sources.sha256");
			if (!SHA256_HEX.matcher(sha256).matches()) {
				throw fail("sources.sha256 must be 64 lowercase hex characters, got " + sha256);
			}
			result.add(new Source(text(item.get("source_id"), "sources.source_id"), text(item.get("path"), "sources.path"),
					sha256));
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
	private static void requireExactFields(JsonNode node, List<String> expected, String context) {
		Set<String> present = new TreeSet<>();
		for (Map.Entry<String, JsonNode> entry : node.properties()) {
			present.add(entry.getKey());
		}
		Set<String> missing = new TreeSet<>(expected);
		missing.removeAll(present);
		Set<String> unknown = new TreeSet<>(present);
		unknown.removeAll(expected);
		if (!missing.isEmpty() || !unknown.isEmpty()) {
			throw fail(context + " must have exactly the schema-" + SCHEMA_VERSION + " fields; missing " + missing
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

	private static IllegalArgumentException fail(String message) {
		return new IllegalArgumentException("parking_tariffs: " + message);
	}
}
