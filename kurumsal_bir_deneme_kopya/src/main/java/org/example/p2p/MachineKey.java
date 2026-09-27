package org.example.p2p;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Key material bound to this machine, used to seal secrets on disk (AES-256-GCM) so that a copied file is useless on
 * another computer.
 *
 * <p>The machine identifier comes from the operating system: the {@code MachineGuid} of the Windows registry,
 * {@code /etc/machine-id} on Linux, {@code IOPlatformUUID} on macOS. It never leaves the machine and is never
 * stored; only {@code SHA-256("dwb-machine-v1" | source | id)} is kept in memory. Every sealed blob carries its own
 * random salt, so the AES key differs per file: {@code key = SHA-256(material | salt)}.</p>
 *
 * <p>This protects against a copied or leaked file (backup, cloned disk, stolen profile folder), not against an
 * attacker who already runs code as the user on this machine: such an attacker can read the machine id as well.</p>
 */
public final class MachineKey {

    private static final System.Logger LOG = System.getLogger(MachineKey.class.getName());

    private static final byte[] SEAL_MAGIC = {'D', 'W', 'B', 'S', 1};
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final long PROBE_TIMEOUT_SECONDS = 5;
    private static final Pattern WINDOWS_GUID = Pattern.compile("MachineGuid\\s+REG_SZ\\s+([0-9A-Fa-f-]{16,64})");
    private static final Pattern MAC_UUID = Pattern.compile("\"IOPlatformUUID\"\\s*=\\s*\"([0-9A-Fa-f-]{16,64})\"");

    private final byte[] material;
    private final String source;

    private MachineKey(String source, String machineId) {
        this.source = source;
        MessageDigest d = org.example.util.Hashing.sha256();
        d.update("dwb-machine-v1\0".getBytes(StandardCharsets.US_ASCII));
        d.update(source.getBytes(StandardCharsets.UTF_8));
        d.update((byte) 0);
        d.update(machineId.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        this.material = d.digest();
    }

    /** A key from an explicitly given machine identifier (tests, or an id IT provisions by other means). */
    public static MachineKey of(String machineId) {
        if (machineId == null || machineId.isBlank()) {
            throw new IllegalArgumentException("machine id must not be blank");
        }
        return new MachineKey("explicit", machineId);
    }

    /**
     * Reads this machine's operating-system identifier.
     *
     * @throws IOException when no identifier can be read: the device key is then not created at all (fail closed)
     */
    public static MachineKey detect() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String id = null;
        String source;
        if (os.contains("win")) {
            source = "windows-machineguid";
            String root = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            String out = run(Path.of(root, "System32", "reg.exe").toString(), "query",
                    "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid", "/reg:64");
            Matcher m = out == null ? null : WINDOWS_GUID.matcher(out);
            id = m != null && m.find() ? m.group(1) : null;
        } else if (os.contains("mac")) {
            source = "macos-platformuuid";
            String out = run("/usr/sbin/ioreg", "-rd1", "-c", "IOPlatformExpertDevice");
            Matcher m = out == null ? null : MAC_UUID.matcher(out);
            id = m != null && m.find() ? m.group(1) : null;
        } else {
            source = "linux-machine-id";
            for (String file : new String[]{"/etc/machine-id", "/var/lib/dbus/machine-id"}) {
                try {
                    String v = Files.readString(Path.of(file), StandardCharsets.US_ASCII).strip();
                    if (v.matches("[0-9a-fA-F]{32}")) {
                        id = v;
                        break;
                    }
                } catch (IOException | RuntimeException e) {
                    // try the next location
                }
            }
        }
        if (id == null || id.isBlank()) {
            throw new IOException("cannot read this machine's identifier (" + source + "); the device key cannot be"
                    + " sealed to this computer");
        }
        return new MachineKey(source, id);
    }

    /** Where the identifier came from (for diagnostics; never the identifier itself). */
    public String source() {
        return source;
    }

    /** {@code DWBS 1 | salt | iv | AES-GCM(plain)}; {@code aad} is authenticated but not stored. */
    public byte[] seal(byte[] plain, byte[] aad) {
        byte[] salt = LanSecurity.nonce(SALT_BYTES);
        byte[] iv = LanSecurity.nonce(IV_BYTES);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(salt), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(SEAL_MAGIC);
            cipher.updateAAD(aad);
            byte[] body = cipher.doFinal(plain);
            byte[] out = new byte[SEAL_MAGIC.length + SALT_BYTES + IV_BYTES + body.length];
            System.arraycopy(SEAL_MAGIC, 0, out, 0, SEAL_MAGIC.length);
            System.arraycopy(salt, 0, out, SEAL_MAGIC.length, SALT_BYTES);
            System.arraycopy(iv, 0, out, SEAL_MAGIC.length + SALT_BYTES, IV_BYTES);
            System.arraycopy(body, 0, out, SEAL_MAGIC.length + SALT_BYTES + IV_BYTES, body.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable", e);
        }
    }

    /**
     * Opens a blob made by {@link #seal} on this machine.
     *
     * @throws AEADBadTagException when it was sealed on another machine, with other {@code aad}, or was altered
     */
    public byte[] open(byte[] sealed, byte[] aad) throws GeneralSecurityException {
        int header = SEAL_MAGIC.length + SALT_BYTES + IV_BYTES;
        if (sealed.length < header + TAG_BITS / 8 || !Arrays.equals(sealed, 0, SEAL_MAGIC.length, SEAL_MAGIC, 0,
                SEAL_MAGIC.length)) {
            throw new AEADBadTagException("not a sealed blob");
        }
        byte[] salt = Arrays.copyOfRange(sealed, SEAL_MAGIC.length, SEAL_MAGIC.length + SALT_BYTES);
        byte[] iv = Arrays.copyOfRange(sealed, SEAL_MAGIC.length + SALT_BYTES, header);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(salt), new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(SEAL_MAGIC);
        cipher.updateAAD(aad);
        return cipher.doFinal(sealed, header, sealed.length - header);
    }

    private SecretKeySpec key(byte[] salt) {
        MessageDigest d = org.example.util.Hashing.sha256();
        d.update(material);
        d.update(salt);
        return new SecretKeySpec(d.digest(), "AES");
    }

    /** Output of a short-lived OS tool, or {@code null} when it is missing, fails or hangs. */
    private static String run(String... command) {
        Objects.requireNonNull(command);
        Process p = null;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getOutputStream().close();
            // The tools print a few KB at most (well inside the pipe buffer), so waiting first cannot dead-lock, and
            // a tool that hangs is abandoned after the timeout instead of blocking a read forever.
            if (!p.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS) || p.exitValue() != 0) {
                return null;
            }
            try (InputStream in = p.getInputStream()) {
                return new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "{0} unavailable: {1}", command[0], e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }
}
