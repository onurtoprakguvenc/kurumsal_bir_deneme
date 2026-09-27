package org.yazi.gateway;

import java.util.Optional;

/**
 * Where the API key comes from. Never from source code. The desktop app will add Windows Credential Manager;
 * until then the environment is the only source.
 */
public final class ApiKeys {

    private ApiKeys() {}

    public static final String ENV_VARIABLE = "GEMINI_API_KEY";

    public static Optional<String> fromEnvironment() {
        String value = System.getenv(ENV_VARIABLE);
        return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value.strip());
    }
}
