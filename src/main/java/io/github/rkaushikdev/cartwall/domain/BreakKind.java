package io.github.rkaushikdev.cartwall.domain;

/**
 * What a stopset is for. A network break belongs to the network and the legal ID belongs to the
 * licence; neither will take a local spot however empty it looks.
 */
public enum BreakKind {
    LOCAL("local"),
    NETWORK("network"),
    LEGAL_ID("legal id"),
    PROMO("promo");

    private final String value;

    BreakKind(String value) {
        this.value = value;
    }

    /** The short name stored in the database. */
    public String value() {
        return value;
    }

    /** Whether traffic may put a paid spot in it. */
    public boolean sells() {
        return this == LOCAL || this == PROMO;
    }

    public static BreakKind fromValue(String value) {
        for (BreakKind kind : values()) {
            if (kind.value.equals(value)) {
                return kind;
            }
        }
        throw new ClockError("'" + value + "' is not a kind of break");
    }
}
