package org.yazi.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SyntaxCloserTest {

    @Test
    void closesNestedBracketsInsideQuoteInOrder() {
        assertEquals("\"Run (now)\"", SyntaxCloser.closeHanging("\"Run (now"));
        assertEquals("(he said \"hi\")", SyntaxCloser.closeHanging("(he said \"hi"));
    }

    @Test
    void closesCurlyAndGuillemetQuotes() {
        assertEquals("He said “go”", SyntaxCloser.closeHanging("He said “go"));
        assertEquals("«git»", SyntaxCloser.closeHanging("«git"));
    }

    @Test
    void leavesBalancedTextAlone() {
        assertEquals("\"Done.\" (ok)", SyntaxCloser.closeHanging("\"Done.\" (ok)"));
    }

    @Test
    void onlyInspectsLastParagraph() {
        String speech = "“First paragraph of a long speech.\n“Second paragraph.”";
        assertEquals(speech, SyntaxCloser.closeHanging(speech));
    }

    @Test
    void straightQuoteClosesThroughAbandonedBracket() {
        assertEquals("\"a (b\" c", SyntaxCloser.closeHanging("\"a (b\" c"));
    }
}
