package io.github.rkaushikdev.cartwall.domain;

/** How a line's carts take turns: evenly, by weight, or strictly in order. */
public enum RotationKind {
    EVEN("even"),
    WEIGHTED("weighted"),
    SEQUENTIAL("sequential");

    private final String value;

    RotationKind(String value) {
        this.value = value;
    }

    /** The short name stored in the database and sent over the API. */
    public String value() {
        return value;
    }

    public boolean interleaves() {
        return this != SEQUENTIAL;
    }

    public static RotationKind fromValue(String value) {
        for (RotationKind kind : values()) {
            if (kind.value.equals(value)) {
                return kind;
            }
        }
        throw new RotationError("'" + value + "' is not a kind of rotation");
    }
}
