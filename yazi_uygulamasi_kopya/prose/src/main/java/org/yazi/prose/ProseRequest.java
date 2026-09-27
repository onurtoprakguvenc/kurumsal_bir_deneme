package org.yazi.prose;

import org.yazi.model.BufferSnapshot;
import org.yazi.model.Tier;

import java.util.Objects;

/**
 * Everything one prose operation needs. Built fresh for every action from the current buffer; nothing from an
 * earlier operation is carried over.
 *
 * @param brief the writer's pinned notes for this document (may be empty); capped by the context policy
 */
public record ProseRequest(BufferSnapshot snapshot, ProseIntent intent, Tier tier, String brief) {

    public ProseRequest {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(intent, "intent");
        tier = (tier == null) ? Tier.BALANCED : tier;
        brief = (brief == null) ? "" : brief;
    }
}
