package org.yazi.text;

import org.junit.jupiter.api.Test;
import org.yazi.model.Span;

import static org.junit.jupiter.api.Assertions.*;

class BlockLocatorTest {

    @Test
    void findsParagraphBeforeCaret() {
        String text = "First para.\n\nSecond para line one.\nline two.\n\n";
        Span block = BlockLocator.precedingBlock(text, text.length()).orElseThrow();
        assertEquals("Second para line one.\nline two.", text.substring(block.start(), block.end()));
    }

    @Test
    void handlesWindowsLineEndings() {
        String text = "First.\r\n\r\nSecond.";
        Span block = BlockLocator.precedingBlock(text, text.length()).orElseThrow();
        assertEquals("Second.", text.substring(block.start(), block.end()));
    }

    @Test
    void emptyBufferHasNoBlock() {
        assertTrue(BlockLocator.precedingBlock("", 0).isEmpty());
        assertTrue(BlockLocator.precedingBlock("   \n\n ", 5).isEmpty());
    }

    @Test
    void recognisesStructuralOperators() {
        assertTrue(DirectiveSyntax.isStructuralOperator("s/sword/blade/g"));
        assertTrue(DirectiveSyntax.isStructuralOperator("kılıç -> bıçak"));
        assertFalse(DirectiveSyntax.isStructuralOperator("describe what she sees"));
        assertFalse(DirectiveSyntax.isStructuralOperator(null));
    }
}
