package org.yazi.gateway.schema;

import java.util.Set;

/**
 * Per-request adjustments to a derived schema.
 *
 * @param excludedProperties top-level properties removed from the schema entirely, so the model does not spend
 *                           tokens generating them (e.g. the image pipeline's optional {@code regionalPasses})
 */
public record SchemaOptions(Set<String> excludedProperties) {

    public SchemaOptions {
        excludedProperties = (excludedProperties == null) ? Set.of() : Set.copyOf(excludedProperties);
    }

    public static SchemaOptions none() {
        return new SchemaOptions(Set.of());
    }

    public static SchemaOptions excluding(String... properties) {
        return new SchemaOptions(Set.of(properties));
    }
}
