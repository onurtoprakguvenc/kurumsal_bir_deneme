package org.yazi.gateway;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Server-Sent Events parser: joins the {@code data:} lines of each event and hands the payload over when
 * the event ends (blank line or end of stream). Comments and other fields are ignored, as is a {@code [DONE]}
 * sentinel.
 */
final class SseEventReader {

    private SseEventReader() {}

    @FunctionalInterface
    interface Handler {
        void onData(String data) throws GatewayException;
    }

    static void read(InputStream in, Handler handler) throws IOException, GatewayException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        boolean hasData = false;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (hasData) {
                    dispatch(data, handler);
                    hasData = false;
                }
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            String field = (colon < 0) ? line : line.substring(0, colon);
            if (!field.equals("data")) {
                continue;
            }
            String value = (colon < 0) ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if (hasData) {
                data.append('\n');
            }
            data.append(value);
            hasData = true;
        }
        if (hasData) {
            dispatch(data, handler);
        }
    }

    private static void dispatch(StringBuilder data, Handler handler) throws GatewayException {
        String payload = data.toString();
        data.setLength(0);
        if (!payload.isBlank() && !payload.strip().equals("[DONE]")) {
            handler.onData(payload);
        }
    }
}
