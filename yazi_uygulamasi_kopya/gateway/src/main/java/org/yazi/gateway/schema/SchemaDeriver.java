package org.yazi.gateway.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Derives a Gemini response schema (OpenAPI subset) from a record type, so the Java type is the single source of
 * truth for structured output. Replaces the hand-written schema strings in prompt_gelistirme and
 * {@code SceneContract.buildGeminiResponseSchema} in image_generate_prompt_improve.
 *
 * <ul>
 *   <li>Property names follow {@code @JsonProperty} when present, so the schema and Jackson parsing agree.</li>
 *   <li>{@code propertyOrdering} follows record component order; the model generates fields in that order, so
 *       put the fields that later ones depend on first.</li>
 *   <li>Every component is required unless marked {@link SchemaOptional}.</li>
 * </ul>
 */
public final class SchemaDeriver {

    private SchemaDeriver() {}

    private static final ObjectMapper NODES = new ObjectMapper();

    public static ObjectNode derive(Class<?> type, SchemaOptions options) {
        return derive(NODES, type, options);
    }

    public static ObjectNode derive(ObjectMapper json, Class<?> type, SchemaOptions options) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException("Structured output type must be a record: " + type.getName());
        }
        Set<String> excluded = (options == null) ? Set.of() : options.excludedProperties();
        Set<String> known = new LinkedHashSet<>();
        for (RecordComponent rc : type.getRecordComponents()) {
            known.add(propertyName(rc));
        }
        for (String name : excluded) {
            if (!known.contains(name)) {
                throw new IllegalArgumentException("Cannot exclude unknown property '" + name + "' of "
                        + type.getSimpleName() + "; known: " + known);
            }
        }
        return objectSchema(json, type, excluded, new HashSet<>());
    }

    private static ObjectNode objectSchema(ObjectMapper json, Class<?> record, Set<String> excluded, Set<Class<?>> path) {
        if (!path.add(record)) {
            throw new IllegalArgumentException("Recursive record types are not supported: " + record.getName());
        }
        ObjectNode node = json.createObjectNode();
        node.put("type", "OBJECT");
        ObjectNode properties = node.putObject("properties");
        ArrayNode ordering = json.createArrayNode();
        ArrayNode required = json.createArrayNode();

        for (RecordComponent rc : record.getRecordComponents()) {
            String name = propertyName(rc);
            if (excluded.contains(name)) {
                continue;
            }
            ObjectNode property = schemaFor(json, rc.getGenericType(), path);
            SchemaDescription description = rc.getAnnotation(SchemaDescription.class);
            if (description != null) {
                property.put("description", description.value());
            }
            properties.set(name, property);
            ordering.add(name);
            if (!rc.isAnnotationPresent(SchemaOptional.class)) {
                required.add(name);
            }
        }
        node.set("propertyOrdering", ordering);
        if (!required.isEmpty()) {
            node.set("required", required);
        }
        path.remove(record);
        return node;
    }

    private static ObjectNode schemaFor(ObjectMapper json, Type type, Set<Class<?>> path) {
        if (type instanceof Class<?> c) {
            if (c == String.class || CharSequence.class.isAssignableFrom(c)) {
                return typed(json, "STRING");
            }
            if (c == boolean.class || c == Boolean.class) {
                return typed(json, "BOOLEAN");
            }
            if (c == int.class || c == long.class || c == short.class || c == byte.class
                    || c == Integer.class || c == Long.class || c == Short.class || c == Byte.class
                    || c == BigInteger.class) {
                return typed(json, "INTEGER");
            }
            if (c == double.class || c == float.class || c == Double.class || c == Float.class
                    || c == BigDecimal.class) {
                return typed(json, "NUMBER");
            }
            if (c.isEnum()) {
                ObjectNode node = typed(json, "STRING");
                ArrayNode values = node.putArray("enum");
                for (Object constant : c.getEnumConstants()) {
                    values.add(enumValue(c, (Enum<?>) constant));
                }
                return node;
            }
            if (c.isRecord()) {
                return objectSchema(json, c, Set.of(), path);
            }
            if (c.isArray()) {
                ObjectNode node = typed(json, "ARRAY");
                node.set("items", schemaFor(json, c.getComponentType(), path));
                return node;
            }
        }
        if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw
                && Collection.class.isAssignableFrom(raw)) {
            ObjectNode node = typed(json, "ARRAY");
            node.set("items", schemaFor(json, p.getActualTypeArguments()[0], path));
            return node;
        }
        throw new IllegalArgumentException("Unsupported type in structured output schema: " + type.getTypeName());
    }

    private static ObjectNode typed(ObjectMapper json, String type) {
        ObjectNode node = json.createObjectNode();
        node.put("type", type);
        return node;
    }

    private static String propertyName(RecordComponent rc) {
        JsonProperty annotation = rc.getAccessor().getAnnotation(JsonProperty.class);
        if (annotation != null && !annotation.value().isEmpty()) {
            return annotation.value();
        }
        return rc.getName();
    }

    private static String enumValue(Class<?> enumType, Enum<?> constant) {
        try {
            JsonProperty annotation = enumType.getField(constant.name()).getAnnotation(JsonProperty.class);
            return (annotation != null && !annotation.value().isEmpty()) ? annotation.value() : constant.name();
        } catch (NoSuchFieldException e) {
            return constant.name();
        }
    }
}
