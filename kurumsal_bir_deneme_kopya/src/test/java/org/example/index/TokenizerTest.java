package org.example.index;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenizerTest {

    private final Tokenizer tokenizer = new Tokenizer();

    private record Token(String term, int position, int start, int end) {
    }

    private List<Token> tokens(String text) {
        List<Token> out = new ArrayList<>();
        tokenizer.tokenize(text, (term, position, start, end) -> out.add(new Token(term, position, start, end)));
        return out;
    }

    @Test
    void foldsCaseDotlessIAndDiacritics() {
        assertEquals(List.of("sirket", "istanbul", "ilac", "cagri", "ozet", "uc", "cafe"),
                tokens("ŞİRKET İstanbul ILAÇ çağrı ÖZET üç café").stream().map(Token::term).toList());
    }

    @Test
    void stopWordsKeepTheirPositionsAndOffsetsPointAtSurfaceForms() {
        String text = "kira ve bedel";
        List<Token> t = tokens(text);
        assertEquals(2, t.size());
        assertEquals(new Token("kira", 0, 0, 4), t.get(0));
        assertEquals(2, t.get(1).position(), "the stop word 've' still counts as position 1");
        assertEquals("bedel", text.substring(t.get(1).start(), t.get(1).end()));
    }

    @Test
    void singleLettersAreDroppedButDigitsAreKept() {
        assertEquals(List.of("5", "yil"), tokens("a 5 yıl").stream().map(Token::term).toList());
    }

    @Test
    void combiningMarksAreFoldedAway() {
        // "s" + combining cedilla, "i" + combining dot above
        assertEquals(List.of("sirket"), tokens("şirket").stream().map(Token::term).toList());
    }

    @Test
    void overlongTokensAreSkippedButCounted() {
        String longWord = "x".repeat(Tokenizer.MAX_TOKEN_CHARS + 5);
        List<Token> t = tokens(longWord + " sonra");
        assertEquals(1, t.size());
        assertEquals(1, t.getFirst().position());
    }

    @Test
    void supplementaryLettersFormTokens() {
        String text = "𝐀𝐁 test"; // mathematical bold A, B
        assertEquals(2, tokens(text).size());
    }

    @Test
    void normalizeTermAndFoldPrefix() {
        assertEquals("kiraci", tokenizer.normalizeTerm("KİRACI"));
        assertNull(tokenizer.normalizeTerm("ve"), "stop words normalize to nothing");
        assertNull(tokenizer.normalizeTerm("  ...  "));
        assertEquals("ve", tokenizer.foldPrefix("ve"), "a stop word is still a prefix");
        assertEquals("k", tokenizer.foldPrefix("K"));
        assertEquals("sirk", tokenizer.foldPrefix("  Şirk-et"), "first run only");
        assertNull(tokenizer.foldPrefix("-*-"));
        assertNull(tokenizer.foldPrefix(null));
        assertTrue(tokenizer.foldPrefix("x".repeat(100)).length() <= Tokenizer.MAX_TOKEN_CHARS);
    }
}
