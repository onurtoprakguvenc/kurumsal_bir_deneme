package org.yazi.motion.engines;

import org.yazi.motion.domain.AssetType;
import org.yazi.motion.domain.ReferenceAssetBinding;
import org.yazi.motion.domain.SpatialPosition;
import org.yazi.motion.domain.TemporalSegment;
import org.yazi.motion.domain.exception.AssetBoundaryException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Build step 07. Repairs (reported to the repair log): end beyond the duration is clamped to it,
 * weight is clamped to [0, 1] (missing/NaN weight becomes 1.0), missing type/position default to STYLE/SUBJECT.
 * Failures: missing id/uri, start &lt; 0, start &gt;= end, start &gt;= duration. Input order is preserved.
 */
public final class ReferenceAssetBinder implements IReferenceAssetBinder {

    private final Consumer<String> repairLog;

    public ReferenceAssetBinder() {
        this(msg -> { });
    }

    public ReferenceAssetBinder(Consumer<String> repairLog) {
        this.repairLog = repairLog;
    }

    @Override
    public List<ReferenceAssetBinding> bindAssets(List<ReferenceAssetBinding> assets, double totalDurationSec) {
        if (assets == null || assets.isEmpty()) return List.of();
        List<ReferenceAssetBinding> out = new ArrayList<>(assets.size());
        for (int idx = 0; idx < assets.size(); idx++) {
            ReferenceAssetBinding a = assets.get(idx);
            if (a == null || a.assetId() == null || a.assetId().isBlank()) {
                throw new AssetBoundaryException("Asset at index " + idx + " lacks a valid 'asset_id'.", Map.of("index", idx));
            }
            if (a.uri() == null || a.uri().isBlank()) {
                throw new AssetBoundaryException("Asset '" + a.assetId() + "' lacks a valid 'uri'.", Map.of("asset_id", a.assetId()));
            }
            double start = a.temporalStartSec(), end = a.temporalEndSec();
            Map<String, Object> details = Map.of("asset_id", a.assetId(), "temporal_range_sec", List.of(start, end),
                    "target_duration_sec", totalDurationSec);
            if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || start >= end) {
                throw new AssetBoundaryException("Asset '" + a.assetId() + "' has invalid temporal range boundaries: ["
                        + start + ", " + end + "].", details);
            }
            if (start >= totalDurationSec) {
                throw new AssetBoundaryException("Asset '" + a.assetId() + "' start second (" + start
                        + "s) exceeds or equals total duration (" + totalDurationSec + "s).", details);
            }
            double clampedEnd = TemporalContinuity.roundToMillis(Math.min(end, totalDurationSec));
            double clampedStart = TemporalContinuity.roundToMillis(start);
            if (end > totalDurationSec) {
                repairLog.accept("asset '" + a.assetId() + "': temporal range [" + fmt(start) + ", " + fmt(end)
                        + "] clamped to [" + fmt(clampedStart) + ", " + fmt(clampedEnd) + "]");
            }
            double weight = Double.isNaN(a.weight()) ? 1.0 : Math.min(1.0, Math.max(0.0, a.weight()));
            if (!Double.isNaN(a.weight()) && weight != a.weight()) {
                repairLog.accept("asset '" + a.assetId() + "': weight " + a.weight() + " clamped to " + fmt(weight));
            }
            AssetType type = a.assetType() != null ? a.assetType() : AssetType.STYLE;
            SpatialPosition position = a.spatialPosition() != null ? a.spatialPosition() : SpatialPosition.SUBJECT;
            out.add(a.withNormalized(type, position, clampedStart, clampedEnd, weight));
        }
        return List.copyOf(out);
    }

    /** Assets active during a segment: any overlap with [start, end). */
    public static List<ReferenceAssetBinding> activeDuring(TemporalSegment segment, List<ReferenceAssetBinding> bindings) {
        return bindings.stream().filter(b -> b.overlaps(segment.startSecond(), segment.endSecond())).toList();
    }

    private static String fmt(double v) {
        return PromptPackageCompiler.fmt(v);
    }
}
