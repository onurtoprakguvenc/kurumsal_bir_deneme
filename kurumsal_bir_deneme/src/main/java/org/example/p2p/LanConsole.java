package org.example.p2p;

import java.io.PrintStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tagged transfer lines on the process console ({@code [TRANSFER-START]}, {@code [TRANSFER-SUCCESS]},
 * {@code [TRANSFER-FAILED]}, {@code [TRANSFER-LISTEN]}), so the terminal of a receiving node never stays silent and a
 * firewall or binding problem can be diagnosed from both machines. Never called from a {@code Progress} callback.
 */
final class LanConsole {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private LanConsole() {
    }

    static void start(String message) {
        print("TRANSFER-START", message);
    }

    static void success(String message) {
        print("TRANSFER-SUCCESS", message);
    }

    static void failed(String message) {
        print("TRANSFER-FAILED", message);
    }

    static void listen(String message) {
        print("TRANSFER-LISTEN", message);
    }

    /** A readable failure reason; a socket timeout is named as such. */
    static String reason(Throwable e) {
        String message = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
        return e instanceof SocketTimeoutException ? "timeout: " + message : message;
    }

    /** IPv4 addresses of the interfaces that are up (loopback excluded), for the listening line. */
    static List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address) {
                        out.add(a.getHostAddress());
                    }
                }
            }
        } catch (SocketException | RuntimeException ignored) {
            // informational only
        }
        return out;
    }

    private static void print(String tag, String message) {
        String line = LocalTime.now().format(TIME) + " [" + tag + "] " + message;
        PrintStream out = System.out;
        byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            out.write(bytes, 0, bytes.length);
            out.flush();
        }
    }
}
