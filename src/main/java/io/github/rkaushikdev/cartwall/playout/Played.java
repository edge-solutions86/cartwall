package io.github.rkaushikdev.cartwall.playout;

import java.util.Locale;
import java.util.Map;

/**
 * What the automation says happened to one element.
 *
 * <p>The vocabulary is the automation's and it is fixed. Anything it sends that is not in the
 * table is kept as {@link #UNKNOWN} rather than dropped, because a status nobody has seen before
 * is exactly the thing somebody needs to look at.
 */
public enum Played {
    PLAYED("played"),
    SKIPPED("skipped"),
    ERROR("error"),
    /** Started and cut off, usually by a hard join. */
    TRUNCATED("truncated"),
    UNKNOWN("unknown");

    /** What the automation writes for each status, lower cased before lookup. */
    static final Map<String, Played> CODES = Map.of(
            "play", PLAYED,
            "played", PLAYED,
            "ok", PLAYED,
            "skip", SKIPPED,
            "skipped", SKIPPED,
            "err", ERROR,
            "error", ERROR,
            "fail", ERROR,
            "trunc", TRUNCATED,
            "truncated", TRUNCATED);

    private final String value;

    Played(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Whether anything went out. Truncated did, and may still be billable. */
    public boolean aired() {
        return this == PLAYED || this == TRUNCATED;
    }

    /** The status for what the automation wrote, or {@link #UNKNOWN}. */
    public static Played written(String text) {
        return CODES.getOrDefault(text.strip().toLowerCase(Locale.ROOT), UNKNOWN);
    }
}
