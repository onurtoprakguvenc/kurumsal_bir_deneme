package org.example.p2p;

import java.util.Locale;
import java.util.Optional;

/** What a paired device may do on this node. */
public enum DeviceRole {
    /**
     * A colleague's computer: receives this node's catalog (minus documents whose access list leaves it out), pulls
     * shared documents and may push documents here.
     */
    FULL_PEER,
    /**
     * An external or visiting machine: never sees the catalog or the index, never pushes; it can only receive files
     * sent to it on purpose ({@code send}) or pull a file granted to it once ({@code grant}). The grant is used up by
     * the first complete, verified transfer, and the session ends with it.
     */
    RESTRICTED_GUEST;

    public static Optional<DeviceRole> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String t = text.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        if (t.startsWith("ROLE=")) {
            t = t.substring(5);
        }
        return switch (t) {
            case "FULL_PEER", "FULL", "PEER" -> Optional.of(FULL_PEER);
            case "RESTRICTED_GUEST", "GUEST", "RESTRICTED" -> Optional.of(RESTRICTED_GUEST);
            default -> Optional.empty();
        };
    }
}
