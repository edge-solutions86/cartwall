package io.github.rkaushikdev.cartwall.domain;

/**
 * What a political spot is, which decides whether the charge rule applies. Issue advertising is
 * never entitled to the lowest unit charge, however political it sounds.
 */
public enum Use {
    /** The candidate's own voice or image, placed by their committee. */
    CANDIDATE("candidate"),
    /** Somebody else's advertising about an issue or a candidate. */
    ISSUE("issue");

    private final String value;

    Use(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean entitled() {
        return this == CANDIDATE;
    }
}
