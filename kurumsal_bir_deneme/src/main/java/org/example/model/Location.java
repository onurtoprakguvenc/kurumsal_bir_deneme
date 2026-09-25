package org.example.model;

/**
 * Coordinate of one token occurrence.
 *
 * @param docId      SHA-256 of the document
 * @param chunkIndex chunk that contains the occurrence
 * @param position   token ordinal within the chunk (stop words count, so phrase distances are exact)
 * @param offset     character offset within the normalized document text
 * @param length     length of the surface form in characters
 */
public record Location(String docId, int chunkIndex, int position, long offset, int length) {
}
