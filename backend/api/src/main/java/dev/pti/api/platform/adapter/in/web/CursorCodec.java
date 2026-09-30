package dev.pti.api.platform.adapter.in.web;

import dev.pti.api.platform.domain.KeysetCursor;
import dev.pti.api.platform.domain.ValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The opaque keyset cursor of DOC-31 §5.1: {@code base64url(JSON {"v": 1, "k": [<sort key of the last row>], "f":
 * "<first 4 hex characters of the SHA-256 of the normalised filter>"})}, without padding. It is not signed, because it
 * opens no data beyond what the caller may already read. The filter hash makes a cursor of one query useless for
 * another (for example {@code status=NEW} and {@code status=MANUAL}), which is a 400 on the field {@code cursor}.
 */
public final class CursorCodec {

    static final int VERSION = 1;
    static final String FIELD = "cursor";
    private static final int FINGERPRINT_LENGTH = 4;

    private final JsonMapper mapper;

    public CursorCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * The fingerprint of a query's filters: names sorted, empty values dropped, multi-valued filters sorted and
     * joined, so {@code status=NEW,MANUAL} and {@code status=MANUAL&status=NEW} give the same value.
     */
    public static String fingerprint(Map<String, ?> filters) {
        Map<String, String> normalised = new TreeMap<>();
        filters.forEach((name, value) -> {
            String text = normalise(value);
            if (!text.isEmpty()) {
                normalised.put(name, text);
            }
        });
        String canonical = normalised.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
        return sha256Prefix(canonical);
    }

    private static String normalise(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Iterable<?> values) {
            List<String> parts = new ArrayList<>();
            values.forEach(part -> parts.add(String.valueOf(part)));
            return parts.stream().sorted().collect(Collectors.joining(","));
        }
        return String.valueOf(value);
    }

    private static String sha256Prefix(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, FINGERPRINT_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }

    public String encode(KeysetCursor cursor, String fingerprint) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("v", VERSION);
        document.put("k", cursor.keys());
        document.put("f", fingerprint);
        byte[] json = mapper.writeValueAsString(document).getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    }

    /**
     * @throws ValidationException on the field {@code cursor} when the token is not decodable, has an unknown version,
     *     or belongs to a query with other filters
     */
    public KeysetCursor decode(String token, String fingerprint) {
        JsonNode document;
        try {
            document = mapper.readTree(Base64.getUrlDecoder().decode(token));
        } catch (IllegalArgumentException | JacksonException e) {
            throw invalid("is not a valid cursor");
        }
        if (!document.isObject()
                || !document.path("v").isInt()
                || document.path("v").asInt() != VERSION) {
            throw invalid("is not a valid cursor");
        }
        JsonNode keys = document.path("k");
        if (!keys.isArray() || keys.isEmpty() || !document.path("f").isString()) {
            throw invalid("is not a valid cursor");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode key : keys) {
            if (!key.isValueNode() || key.isNull()) {
                throw invalid("is not a valid cursor");
            }
            values.add(key.asString());
        }
        if (!document.path("f").asString().equals(fingerprint)) {
            throw invalid("does not belong to this query");
        }
        return new KeysetCursor(values);
    }

    private static ValidationException invalid(String message) {
        return ValidationException.of(FIELD, message);
    }
}
