package io.github.rkaushikdev.cartwall.domain;

/** Where a signal sits on the dial. A translator rebroadcasts an FM on another FM channel. */
public enum Band {
    AM("am"),
    FM("fm"),
    TRANSLATOR("translator");

    private final String value;

    Band(String value) {
        this.value = value;
    }

    /** The short name stored in the database and sent over the API. */
    public String value() {
        return value;
    }

    public boolean isFm() {
        return this == FM || this == TRANSLATOR;
    }

    public static Band fromValue(String value) {
        for (Band band : values()) {
            if (band.value.equals(value)) {
                return band;
            }
        }
        throw new StationError("'" + value + "' is not a band");
    }
}
