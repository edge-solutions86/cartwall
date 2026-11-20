package io.github.rkaushikdev.cartwall.reconcile;

import io.github.rkaushikdev.cartwall.logs.LogLine;
import io.github.rkaushikdev.cartwall.scheduling.LineKey;

/**
 * On the log, not on the as-run. The client did not get it and is owed a makegood.
 *
 * @param scheduled the log line
 * @param reason the as-run's own word for it when a line said it did not go out, otherwise "no
 *     as-run line"
 */
public record Missed(LogLine scheduled, String reason) {

    public static final String NO_LINE = "no as-run line";

    /** Missed with nothing on the as-run to say why. */
    public Missed(LogLine scheduled) {
        this(scheduled, NO_LINE);
    }

    public LineKey key() {
        return new LineKey(scheduled.order(), scheduled.line());
    }

    @Override
    public String toString() {
        return scheduled.order() + "-" + scheduled.line() + " missed: " + reason;
    }
}
