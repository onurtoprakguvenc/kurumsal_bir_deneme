package org.yazi.gateway;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SseEventReaderTest {

    private static List<String> read(String raw) throws Exception {
        List<String> events = new ArrayList<>();
        SseEventReader.read(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)), events::add);
        return events;
    }

    @Test
    void splitsEventsOnBlankLines() throws Exception {
        assertEquals(List.of("{\"a\":1}", "{\"b\":2}"), read("data: {\"a\":1}\r\n\r\ndata: {\"b\":2}\n\n"));
    }

    @Test
    void joinsMultiLineData() throws Exception {
        assertEquals(List.of("line1\nline2"), read("data: line1\ndata: line2\n\n"));
    }

    @Test
    void ignoresCommentsOtherFieldsAndDoneSentinel() throws Exception {
        assertEquals(List.of("x"), read(": keep-alive\nevent: message\nid: 7\ndata: x\n\ndata: [DONE]\n\n"));
    }

    @Test
    void dispatchesFinalEventWithoutTrailingBlankLine() throws Exception {
        assertEquals(List.of("last"), read("data: last"));
    }

    @Test
    void decodesUtf8AcrossTheStream() throws Exception {
        assertEquals(List.of("{\"t\":\"kılıç şimşek\"}"), read("data: {\"t\":\"kılıç şimşek\"}\n\n"));
    }
}
