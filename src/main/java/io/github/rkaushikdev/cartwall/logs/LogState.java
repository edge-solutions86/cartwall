package io.github.rkaushikdev.cartwall.logs;

/**
 * Where a log has got to, and it only moves forward: draft, approved, released to the automation,
 * aired. A change after release reopens it as a new revision rather than editing it in place.
 */
public enum LogState {
    DRAFT("draft"),
    APPROVED("approved"),
    RELEASED("released"),
    AIRED("aired");

    private final String value;

    LogState(String value) {
        this.value = value;
    }

    /** The name stored in the database and sent over the API. */
    public String value() {
        return value;
    }

    public boolean mayEdit() {
        return this == DRAFT;
    }

    /** Whether the automation is working from a copy we no longer control. */
    public boolean isOut() {
        return this == RELEASED || this == AIRED;
    }

    public static LogState fromValue(String value) {
        for (LogState state : values()) {
            if (state.value.equals(value)) {
                return state;
            }
        }
        throw new LogError("'" + value + "' is not a log state");
    }
}
