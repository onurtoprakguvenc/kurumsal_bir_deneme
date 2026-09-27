package org.yazi.model;

/**
 * How much the user is willing to spend on one operation. Tiers change fixed context budgets and model choice;
 * they never make the context grow with the length of the document.
 */
public enum Tier {
    FAST,
    BALANCED,
    DEEP
}
