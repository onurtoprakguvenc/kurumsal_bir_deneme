package org.yazi.text;

/**
 * Measured surface style of a text slice. Pure numbers and flags, computed locally with no model call; the prose
 * pipeline turns them into a style contract and sampling settings.
 *
 * @param lowercaseSentenceStarts the writer usually starts sentences in lowercase
 * @param lineDelimited           mostly one quoted or bulleted unit per line (dialogue scripts, lists)
 * @param gluedPunctuation        the writer usually omits the space after punctuation
 */
public record StyleProfile(
        int sentenceCount,
        double avgWordsPerSentence,
        double sentenceLengthStdDev,
        boolean inlineParentheticals,
        boolean lowercaseSentenceStarts,
        boolean lineDelimited,
        boolean gluedPunctuation) {

    /** Used when there is too little text to measure. */
    public static final StyleProfile NEUTRAL = new StyleProfile(0, 12.0, 3.0, false, false, false, false);

    public boolean isMeasured() {
        return sentenceCount > 0;
    }
}
