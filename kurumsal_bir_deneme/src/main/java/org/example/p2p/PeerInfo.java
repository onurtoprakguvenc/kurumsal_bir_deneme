package org.example.p2p;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Set;

/**
 * Immutable snapshot of a discovered LAN node.
 *
 * @param nodeId         random 64-bit node identity (hex)
 * @param name           human-readable node name (untrusted display text)
 * @param address        source address of the node's datagrams
 * @param transferPort   TCP port of its {@link FileTransferService}
 * @param lastSeen       time of the last valid datagram
 * @param catalogVersion fingerprint of the hash list the node last advertised
 * @param advertised     number of documents the node claims to hold
 * @param hashes         SHA-256 hashes received so far (complete once {@code hashes.size() == advertised})
 */
public record PeerInfo(String nodeId,
                       String name,
                       InetAddress address,
                       int transferPort,
                       Instant lastSeen,
                       String catalogVersion,
                       int advertised,
                       Set<String> hashes) {

    public PeerInfo {
        hashes = Set.copyOf(hashes);
    }

    public InetSocketAddress transferEndpoint() {
        return new InetSocketAddress(address, transferPort);
    }

    public boolean catalogComplete() {
        return hashes.size() == advertised;
    }
}
