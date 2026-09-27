package org.yazi.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamPacerTest {

    private static final long FRAME = 16_666_667L;

    @Test
    void emptyPacerRevealsNothing() {
        StreamPacer p = new StreamPacer();
        assertEquals("", p.take(0));
        assertTrue(p.isEmpty());
        assertEquals("", p.flush());
    }

    @Test
    void aBurstIsSpreadOverSeveralFramesInsteadOfLandingAtOnce() {
        StreamPacer p = new StreamPacer();
        p.offer("x".repeat(300));
        String first = p.take(0);
        assertTrue(first.length() > 0 && first.length() < 300, "first frame: " + first.length());
        int frames = 1;
        long now = 0;
        while (!p.isEmpty() && frames < 1000) {
            now += FRAME;
            p.take(now);
            frames++;
        }
        assertTrue(frames >= 5, "a 300-char burst should flow over several frames, took " + frames);
        assertTrue(frames * FRAME / 1e9 < 1.0, "…but catch up well within a second, took " + frames + " frames");
    }

    @Test
    void revealsEverythingInOrder() {
        StreamPacer p = new StreamPacer();
        StringBuilder shown = new StringBuilder();
        long now = 0;
        for (String fragment : new String[]{"Bir ", "zamanlar ", "", "uzak bir ", "ülkede…"}) {
            p.offer(fragment);
            shown.append(p.take(now += FRAME));
        }
        while (!p.isEmpty()) {
            shown.append(p.take(now += FRAME));
        }
        assertEquals("Bir zamanlar uzak bir ülkede…", shown.toString());
    }

    @Test
    void aSmallBacklogStillMovesAtTheMinimumRate() {
        StreamPacer p = new StreamPacer();
        p.offer("abcdefghij");
        // 90 chars/s over one 60 Hz frame is 1.5 characters, rounded up.
        assertEquals("ab", p.take(0));
        assertEquals("cd", p.take(FRAME));
    }

    @Test
    void aLongPauseBetweenFramesRevealsProportionallyMore() {
        StreamPacer p = new StreamPacer();
        p.offer("y".repeat(400));
        p.take(0);
        int remaining = p.backlog();
        String afterPause = p.take(500_000_000L);   // half a second later
        assertEquals(remaining, afterPause.length(), "after a long frame the backlog is cleared");
    }

    @Test
    void neverSplitsASurrogatePair() {
        StreamPacer p = new StreamPacer();
        String moons = "🌙".repeat(50);
        p.offer(moons);
        StringBuilder shown = new StringBuilder();
        long now = 0;
        while (!p.isEmpty()) {
            String chunk = p.take(now += FRAME);
            assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)), "split pair in: " + chunk);
            shown.append(chunk);
        }
        assertEquals(moons, shown.toString());
    }

    @Test
    void aFragmentEndingMidPairHoldsBackTheHighHalf() {
        StreamPacer p = new StreamPacer();
        String moon = "🌙";
        p.offer("a" + moon.charAt(0));             // the network split the emoji
        assertEquals("a", p.take(0), "never reveal a lone high surrogate");
        assertEquals("", p.take(FRAME), "…even when it is all that is waiting");
        p.offer(String.valueOf(moon.charAt(1)));
        assertEquals(moon, p.take(2 * FRAME));
        p.offer("x" + moon.charAt(0));
        assertEquals("x" + moon.charAt(0), p.flush(), "flush still returns everything");
    }

    @Test
    void flushReturnsTheWholeBacklogAndResetsTheClock() {
        StreamPacer p = new StreamPacer();
        p.offer("hello world");
        p.take(0);
        String rest = p.flush();
        assertTrue("hello world".endsWith(rest));
        assertTrue(p.isEmpty());
        p.offer(null);
        assertTrue(p.isEmpty(), "null fragments are ignored");
    }
}
