package org.yazi.gateway;

import java.util.List;
import java.util.Objects;

/**
 * One stateless model request: a system instruction and a single user turn.
 *
 * <p>There is deliberately no list of messages. Chat history cannot be expressed with this type, so no pipeline
 * can accumulate it by accident.</p>
 *
 * @param thinkingBudget null leaves the model's default; 0 disables thinking on models that allow it
 * @param images         optional inline images, sent before the text in the same user turn
 */
public record ModelCall(
        String model,
        String system,
        String user,
        double temperature,
        int maxOutputTokens,
        Integer thinkingBudget,
        List<InlineImage> images) {

    public ModelCall {
        Objects.requireNonNull(model, "model");
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        system = (system == null) ? "" : system;
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("user text must not be blank");
        }
        if (!(temperature >= 0.0 && temperature <= 2.0)) {
            throw new IllegalArgumentException("temperature must be within [0, 2]: " + temperature);
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive: " + maxOutputTokens);
        }
        if (thinkingBudget != null && thinkingBudget < 0) {
            throw new IllegalArgumentException("thinkingBudget must not be negative: " + thinkingBudget);
        }
        images = (images == null) ? List.of() : List.copyOf(images);
    }

    /** Text-only call with neutral defaults: temperature 0.7, 2048 output tokens, model-default thinking. */
    public static ModelCall of(String model, String system, String user) {
        return new ModelCall(model, system, user, 0.7, 2_048, null, List.of());
    }

    public ModelCall withTemperature(double value) {
        return new ModelCall(model, system, user, value, maxOutputTokens, thinkingBudget, images);
    }

    public ModelCall withMaxOutputTokens(int value) {
        return new ModelCall(model, system, user, temperature, value, thinkingBudget, images);
    }

    public ModelCall withThinkingBudget(Integer value) {
        return new ModelCall(model, system, user, temperature, maxOutputTokens, value, images);
    }

    /** The same request for another model (used by {@link ResilientGateway}'s model fallback). */
    public ModelCall withModel(String value) {
        return new ModelCall(value, system, user, temperature, maxOutputTokens, thinkingBudget, images);
    }

    public ModelCall withImages(List<InlineImage> value) {
        return new ModelCall(model, system, user, temperature, maxOutputTokens, thinkingBudget, value);
    }
}
