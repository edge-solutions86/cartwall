package io.github.rkaushikdev.cartwall.logs;

/** What one line of the log is. Unsold units are on the log as holes the automation never sees. */
public enum Element {
    SPOT("spot"),
    LEGAL_ID("legal id"),
    NETWORK("network"),
    PROMO("promo"),
    UNSOLD("unsold");

    private final String value;

    Element(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Whether the automation gets handed this line. */
    public boolean airs() {
        return this != UNSOLD;
    }
}
