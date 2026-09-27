package org.yazi.motion;

import org.yazi.motion.domain.AspectRatio;
import org.yazi.motion.domain.AssetType;
import org.yazi.motion.domain.RawUserPromptInput;
import org.yazi.motion.domain.ReferenceAssetBinding;
import org.yazi.motion.domain.SpatialPosition;
import org.yazi.motion.domain.StylePreferences;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class TestSupport {
    private TestSupport() {}

    public static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC);
    public static final double EPS = 0.0011;
    public static final String SCENE = "A woman walks through a rainy city at night. "
            + "Then the camera orbits around her. Close-up of her face in slow motion";

    private static final AtomicInteger IDS = new AtomicInteger();

    /** Unique request ids: a failed request_id cannot be reused on the same orchestrator. */
    public static String nextId() {
        return "req-" + IDS.incrementAndGet();
    }

    public static RawUserPromptInput input(String description, double duration) {
        return input(description, duration, List.of());
    }

    public static RawUserPromptInput input(String description, double duration, List<ReferenceAssetBinding> assets) {
        return new RawUserPromptInput(nextId(), description, duration, AspectRatio.RATIO_16_9, assets, StylePreferences.none());
    }

    public static ReferenceAssetBinding asset(String id, double start, double end, double weight) {
        return new ReferenceAssetBinding(id, "https://x.test/" + id + ".png", AssetType.CHARACTER, SpatialPosition.SUBJECT,
                start, end, weight);
    }
}
