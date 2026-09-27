package org.yazi.motion.domain;

/**
 * External visual reference. As raw input every field is untrusted: {@code asset_type} and
 * {@code spatial_position} may be null (defaults STYLE / SUBJECT), weight may be NaN (= not given, defaults to 1.0)
 * or out of range (clamped). The binder returns fully normalized instances.
 */
public record ReferenceAssetBinding(
        String assetId,
        String uri,
        AssetType assetType,
        SpatialPosition spatialPosition,
        double temporalStartSec,
        double temporalEndSec,
        double weight) {

    public ReferenceAssetBinding withNormalized(AssetType type, SpatialPosition position, double start, double end, double w) {
        return new ReferenceAssetBinding(assetId, uri, type, position, start, end, w);
    }

    /** True when the asset's interval intersects [start, end). */
    public boolean overlaps(double start, double end) {
        return temporalStartSec < end && temporalEndSec > start;
    }
}
