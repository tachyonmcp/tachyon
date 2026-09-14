/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.json;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.json.JsonSchema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@link JavaTypeSchemaFactory} backstops {@link JsonSchema#generate(Class)} on a Java-only
 * classpath (no kt-schema in tachyon-core tests).
 */
class JavaTypeSchemaFactoryTest {

    enum Units {
        CELSIUS,
        FAHRENHEIT
    }

    record Reading(double value, Units units) {}

    record Station(
            UUID id,
            String name,
            @Nullable String note,
            Optional<Integer> elevation,
            List<Reading> readings,
            Map<String, Long> counters,
            Instant updatedAt,
            int[] codes) {}

    record Node(String label, List<Node> children) {}

    @SuppressWarnings("unused")
    public static class Pojo {
        public String title;

        public int getCount() {
            return 0;
        }

        public boolean isActive() {
            return true;
        }
    }

    @Test
    void recordSchemaFollowsComponentsAndOptionality() {
        // language=json
        var expected = """
                {
                  "type": "object",
                  "properties": {
                    "id": {"type": "string", "format": "uuid"},
                    "name": {"type": "string"},
                    "note": {"type": "string"},
                    "elevation": {"type": "integer"},
                    "readings": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "value": {"type": "number"},
                          "units": {"type": "string", "enum": ["CELSIUS", "FAHRENHEIT"]}
                        },
                        "required": ["value", "units"]
                      }
                    },
                    "counters": {"type": "object", "additionalProperties": {"type": "integer"}},
                    "updatedAt": {"type": "string", "format": "date-time"},
                    "codes": {"type": "array", "items": {"type": "integer"}}
                  },
                  "required": ["id", "name", "readings", "counters", "updatedAt", "codes"]
                }
                """;
        assertThatJson(JsonSchema.generate(Station.class).json()).isEqualTo(expected);
    }

    @Test
    void recursiveTypeStopsAtBareObject() {
        // language=json
        var expected = """
                {
                  "type": "object",
                  "properties": {
                    "label": {"type": "string"},
                    "children": {"type": "array", "items": {"type": "object"}}
                  },
                  "required": ["label", "children"]
                }
                """;
        assertThatJson(JsonSchema.generate(Node.class).json()).isEqualTo(expected);
    }

    @Test
    void pojoSchemaUsesPublicFieldsAndGettersRequiringOnlyPrimitives() {
        // language=json
        var expected = """
                {
                  "type": "object",
                  "properties": {
                    "active": {"type": "boolean"},
                    "count": {"type": "integer"},
                    "title": {"type": "string"}
                  },
                  "required": ["active", "count"]
                }
                """;
        assertThatJson(JsonSchema.generate(Pojo.class).json()).isEqualTo(expected);
    }

    @Test
    void declinesNonObjectTypes() {
        var factory = new JavaTypeSchemaFactory();

        assertThat(factory.toJsonSchema(String.class)).isEmpty();
        assertThat(factory.toJsonSchema(Units.class)).isEmpty();
        assertThat(factory.toJsonSchema(List.class)).isEmpty();
        assertThat(factory.toJsonSchema(int[].class)).isEmpty();
        assertThat(factory.toJsonSchema(Map.class)).isPresent();
    }
}
