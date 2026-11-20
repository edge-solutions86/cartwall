package io.github.rkaushikdev.cartwall.domain;

/** Where an hour's programming comes from. A preempted hour has no local avails at all. */
public enum Source {
    LOCAL("local"),
    NETWORK("network"),
    /** Sport, remote, special. */
    PREEMPTED("preempted");

    private final String value;

    Source(String value) {
        this.value = value;
    }

    /** The short name stored in the database. */
    public String value() {
        return value;
    }

    public boolean sells() {
        return this != PREEMPTED;
    }

    public static Source fromValue(String value) {
        for (Source source : values()) {
            if (source.value.equals(value)) {
                return source;
            }
        }
        throw new ProgrammingError("'" + value + "' is not a programming source");
    }
}
