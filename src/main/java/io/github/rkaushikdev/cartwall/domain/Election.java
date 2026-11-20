package io.github.rkaushikdev.cartwall.domain;

/**
 * A kind of election, which fixes how long the lowest unit charge window runs before it: 45 days
 * before a primary or a runoff, 60 before a general or a special.
 */
public enum Election {
    PRIMARY("primary"),
    RUNOFF("runoff"),
    GENERAL("general"),
    SPECIAL("special");

    /** Days before a primary during which the lowest unit charge applies. */
    public static final int PRIMARY_WINDOW = 45;

    /** Days before a general or special election. */
    public static final int GENERAL_WINDOW = 60;

    private final String value;

    Election(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public int windowDays() {
        return this == PRIMARY || this == RUNOFF ? PRIMARY_WINDOW : GENERAL_WINDOW;
    }
}
