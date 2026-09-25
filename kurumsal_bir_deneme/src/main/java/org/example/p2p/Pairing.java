package org.example.p2p;

import org.example.util.Hashing;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Optional;

/**
 * Onboarding of a new device with a one-time PIN over an X25519 (Diffie-Hellman) key exchange, on the transfer port.
 *
 * <pre>
 *   server → HELLO  "DWBT" 2 challengeS:32                     (as for every zero-trust session)
 *   client → PAIR   "DWBT" 2 kind=2 ephC:blob keyC:blob nameC:text
 *            server: takes the open PIN (one attempt only) or closes the socket when none is open
 *   server →        ephS:blob keyS:blob nameS:text
 *            both:  T = transcript("pair", challengeS, ephC, keyC, nameC, ephS, keyS, nameS)
 *                   K = SHA-256("dwb-pair-key" | X25519(eph) | T)
 *   client →        HMAC(K, "client" | PIN)  Ed25519(keyC, "pair-client" | T)
 *            server: wrong PIN or signature → close (the PIN is void); else keyC joins trusted-peers
 *   server →        HMAC(K, "server" | PIN)  Ed25519(keyS, "pair-server" | T)
 *            client: verifies both, then keyS joins its trusted-peers as FULL_PEER
 * </pre>
 *
 * <p>The PIN never crosses the network: each side proves it knows the PIN with a MAC keyed by the fresh
 * Diffie-Hellman secret, and each side proves possession of the device key it wants trusted. A passive listener learns
 * nothing about the PIN. Because a 6-digit PIN is short, an active attacker placed between the two machines could try
 * all PINs offline against the client's proof and pair itself instead; both screens therefore show a 6-digit
 * verification code derived from {@code K}, which differs on the two sides whenever anyone sat in between. The admin
 * compares the codes (and the new device's fingerprint) and removes the device if they do not match.</p>
 */
final class Pairing {

    private static final String KEX = "X25519";
    private static final int MAX_EPHEMERAL_BYTES = 128;
    private static final byte[] LABEL = "dwbt2/pair".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LABEL_KEY = "dwb-pair-key".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LABEL_SAS = "dwb-pair-sas".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LABEL_CLIENT_SIG = "dwbt2/pair-client".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LABEL_SERVER_SIG = "dwbt2/pair-server".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CLIENT = "client".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SERVER = "server".getBytes(StandardCharsets.US_ASCII);

    private Pairing() {
    }

    /** Server half; called after the client chose {@code kind=PAIR}. Any failure just returns (socket is closed). */
    static void serve(DataInputStream in, DataOutputStream out, byte[] challengeS, ZeroTrust trust, InetAddress from)
            throws IOException {
        byte[] ephC = FileTransferService.readBlob(in, MAX_EPHEMERAL_BYTES);
        byte[] keyC = FileTransferService.readBlob(in, DeviceIdentity.MAX_PUBLIC_KEY_BYTES);
        String nameC = TrustStore.name(FileTransferService.readText(in));
        Optional<ZeroTrust.PairingTicket> ticket = trust.takePairing();
        String who = nameC + " (" + from.getHostAddress() + ")";
        if (ticket.isEmpty()) {
            LanConsole.failed("Pairing request from " + who + " refused: no pairing PIN is open on this device");
            return;
        }
        if (!DeviceIdentity.validPublicKey(keyC)) {
            LanConsole.failed("Pairing request from " + who + " refused: invalid device key; the PIN is void");
            return;
        }
        byte[] ephS;
        byte[] secret;
        try {
            KeyPair eph = KeyPairGenerator.getInstance(KEX).generateKeyPair();
            ephS = eph.getPublic().getEncoded();
            secret = agree(eph, ephC);
        } catch (GeneralSecurityException e) {
            LanConsole.failed("Pairing request from " + who + " refused: bad key exchange; the PIN is void");
            return;
        }
        String nameS = trust.localName();
        byte[] keyS = trust.identity().publicKey();
        FileTransferService.writeBlob(out, ephS);
        FileTransferService.writeBlob(out, keyS);
        FileTransferService.writeText(out, nameS);
        out.flush();
        byte[] t = DeviceIdentity.transcript(LABEL, challengeS, ephC, keyC, bytes(nameC), ephS, keyS, bytes(nameS));
        byte[] k = key(secret, t);
        byte[] pin = bytes(ticket.get().pin());
        byte[] proofC = FileTransferService.readExact(in, Hashing.SHA256_BYTES);
        byte[] sigC = FileTransferService.readExact(in, DeviceIdentity.SIGNATURE_BYTES);
        if (!MessageDigest.isEqual(proofC, mac(k, CLIENT, pin)) || !DeviceIdentity.verify(keyC, sigC, LABEL_CLIENT_SIG, t)) {
            LanConsole.failed("Pairing with " + who + " failed: wrong PIN or key proof; the PIN is void, create a new"
                    + " one to try again");
            return;
        }
        TrustStore.Device device = trust.trust().add(keyC, nameC, ticket.get().role(), ticket.get().department());
        out.write(mac(k, SERVER, pin));
        out.write(trust.identity().sign(LABEL_SERVER_SIG, t));
        out.flush();
        LanConsole.success("Paired " + who + " as " + device.labels() + " · device "
                + DeviceIdentity.display(device.fingerprint()) + " · verification code " + code(k)
                + " (the new device must show the same code; if not, remove it with the devices command)");
    }

    /** Client half: runs the exchange with an open PIN on the server. */
    static FileTransferService.PairingResult join(DataInputStream in, DataOutputStream out, byte[] challengeS,
                                                  ZeroTrust trust, String pin, String localName) throws IOException {
        KeyPair eph;
        try {
            eph = KeyPairGenerator.getInstance(KEX).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IOException("X25519 is not available in this Java runtime", e);
        }
        byte[] ephC = eph.getPublic().getEncoded();
        byte[] keyC = trust.identity().publicKey();
        String nameC = TrustStore.name(localName == null ? trust.localName() : localName);
        out.write(FileTransferService.MAGIC);
        out.writeByte(FileTransferService.VERSION_ZERO_TRUST);
        out.writeByte(FileTransferService.KIND_PAIR);
        FileTransferService.writeBlob(out, ephC);
        FileTransferService.writeBlob(out, keyC);
        FileTransferService.writeText(out, nameC);
        out.flush();
        byte[] ephS;
        byte[] keyS;
        String nameS;
        try {
            ephS = FileTransferService.readBlob(in, MAX_EPHEMERAL_BYTES);
            keyS = FileTransferService.readBlob(in, DeviceIdentity.MAX_PUBLIC_KEY_BYTES);
            nameS = TrustStore.name(FileTransferService.readText(in));
        } catch (EOFException | SocketException e) {
            throw new FileTransferService.TransferException("The other device has no pairing PIN open (none created,"
                    + " expired after 5 minutes, or already used)");
        }
        byte[] t = DeviceIdentity.transcript(LABEL, challengeS, ephC, keyC, bytes(nameC), ephS, keyS, bytes(nameS));
        byte[] k;
        try {
            k = key(agree(eph, ephS), t);
        } catch (GeneralSecurityException e) {
            throw new FileTransferService.TransferException("The other device sent an invalid key exchange");
        }
        byte[] pinBytes = bytes(pin);
        out.write(mac(k, CLIENT, pinBytes));
        out.write(trust.identity().sign(LABEL_CLIENT_SIG, t));
        out.flush();
        byte[] proofS;
        byte[] sigS;
        try {
            proofS = FileTransferService.readExact(in, Hashing.SHA256_BYTES);
            sigS = FileTransferService.readExact(in, DeviceIdentity.SIGNATURE_BYTES);
        } catch (EOFException | SocketException e) {
            throw new FileTransferService.TransferException("The other device rejected the PIN; it is now void -"
                    + " create a new PIN there and try again");
        }
        if (!MessageDigest.isEqual(proofS, mac(k, SERVER, pinBytes)) || !DeviceIdentity.verify(keyS, sigS,
                LABEL_SERVER_SIG, t)) {
            throw new FileTransferService.TransferException("The other device could not prove the PIN or its key;"
                    + " it was NOT trusted");
        }
        TrustStore.Device device = trust.trust().add(keyS, nameS, DeviceRole.FULL_PEER, "");
        return new FileTransferService.PairingResult(device, code(k));
    }

    private static byte[] agree(KeyPair own, byte[] peerEncoded) throws GeneralSecurityException {
        PublicKey peer = KeyFactory.getInstance(KEX).generatePublic(new X509EncodedKeySpec(peerEncoded));
        KeyAgreement ka = KeyAgreement.getInstance(KEX);
        ka.init(own.getPrivate());
        ka.doPhase(peer, true);
        byte[] secret = ka.generateSecret();
        boolean allZero = true;
        for (byte b : secret) {
            allZero &= b == 0;
        }
        if (allZero) {
            throw new java.security.InvalidKeyException("low-order X25519 point");
        }
        return secret;
    }

    private static byte[] key(byte[] secret, byte[] transcript) {
        byte[] k = Hashing.sha256().digest(DeviceIdentity.transcript(LABEL_KEY, secret, transcript));
        Arrays.fill(secret, (byte) 0);
        return k;
    }

    private static byte[] mac(byte[] key, byte[] role, byte[] pin) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(DeviceIdentity.transcript(role, pin));
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** Short authentication string both screens show: {@code 123 456}. */
    private static String code(byte[] key) {
        byte[] d = Hashing.sha256().digest(DeviceIdentity.transcript(LABEL_SAS, key));
        int v = ((d[0] & 0x7f) << 24 | (d[1] & 0xff) << 16 | (d[2] & 0xff) << 8 | (d[3] & 0xff)) % 1_000_000;
        String s = String.format("%06d", v);
        return s.substring(0, 3) + " " + s.substring(3);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
