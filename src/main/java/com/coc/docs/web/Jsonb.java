package com.coc.docs.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Converts between {@link JsonNode} and the {@code text} form that JDBC hands
 * to a {@code jsonb} column.
 *
 * <p>The driver has no {@code jsonb} type of its own, so every statement writing
 * one of these columns casts explicitly: {@code ?::jsonb}. Doing that through a
 * single helper keeps the cast and the serialisation in one place instead of
 * repeated per repository, where one omission produces a runtime type error the
 * compiler cannot catch.
 *
 * <p>// req: ADR-003
 */
@Component
public class Jsonb {

    private final ObjectMapper mapper;

    public Jsonb(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Serialises for a {@code ?::jsonb} parameter. Null becomes an empty JSON array. */
    public String write(JsonNode node) {
        if (node == null || node.isNull()) {
            return "[]";
        }
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // A JsonNode that cannot be re-serialised means the tree is corrupt
            // in memory, not that the input was bad. Not a 400.
            throw new IllegalStateException("could not serialise a jsonb value", e);
        }
    }

    /** Parses a value read back from a {@code jsonb} column. */
    public JsonNode read(String raw) {
        if (raw == null) {
            return mapper.createArrayNode();
        }
        try {
            return mapper.readTree(raw);
        } catch (JsonProcessingException e) {
            // Postgres validates jsonb on write, so unparseable content here means
            // the column was written by something that bypassed this application.
            throw new IllegalStateException("stored jsonb is not valid JSON", e);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
