package org.yazi.motion.engines;

import org.yazi.motion.domain.ReferenceAssetBinding;

import java.util.List;

public interface IReferenceAssetBinder {
    /**
     * Validates and normalizes asset references against the timeline bounds.
     * @throws org.yazi.motion.domain.exception.AssetBoundaryException if an interval cannot be placed inside [0, duration].
     */
    List<ReferenceAssetBinding> bindAssets(List<ReferenceAssetBinding> assets, double totalDurationSec);
}
