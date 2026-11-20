package io.github.rkaushikdev.cartwall.domain;

/**
 * How far apart a separation rule keeps two spots.
 *
 * <p>A rule scoped to the break is not "zero minutes": two spots in the same break can be four
 * minutes apart while two spots four minutes apart can be in different breaks. The break rule and
 * the minute rule catch different things.
 */
public enum Scope {
    BREAK("break"),
    MINUTES("minutes"),
    HOUR("hour");

    private final String value;

    Scope(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
