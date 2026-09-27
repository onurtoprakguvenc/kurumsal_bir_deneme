package org.example.p2p;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Optional pre-shared-key authentication for LAN traffic (HMAC-SHA256).
 *
 * <p>With a secret configured ({@code DWB_SECRET}), discovery datagrams are signed and time-stamped, and every
 * TCP session starts with a challenge-response, so foreign nodes can neither appear in the peer list nor push
 * files. Without a secret the node runs in open mode: anyone on the LAN segment can talk to it. Transport is
 * not encrypted in either mode; integrity of file content is always enforced by SHA-256.</p>
 */
public final class LanSecurity {

    public static final int MAC_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    private LanSecurity(SecretKeySpec key) {
        this.key = key;
    }

    public static LanSecurity open() {
        return new LanSecurity(null);
    }

    public static LanSecurity fromSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return open();
        }
        byte[] derived = org.example.util.Hashing.sha256()
                .digest(("dwb-lan-v1:" + secret).getBytes(StandardCharsets.UTF_8));
        return new LanSecurity(new SecretKeySpec(derived, "HmacSHA256"));
    }

    public boolean enabled() {
        return key != null;
    }

    public byte[] mac(byte[]... parts) {
        if (key == null) {
            return new byte[MAC_BYTES];
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            for (byte[] part : parts) {
                mac.update(part);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public boolean verify(byte[] candidate, byte[]... parts) {
        return key == null || MessageDigest.isEqual(mac(parts), candidate);
    }

    public static byte[] nonce(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
