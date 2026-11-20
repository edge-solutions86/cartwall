package io.github.rkaushikdev.cartwall.reconcile;

import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.logs.LogLine;
import io.github.rkaushikdev.cartwall.playout.AsRun;
import io.github.rkaushikdev.cartwall.scheduling.LineKey;

/**
 * One scheduled spot and the as-run line that answers for it.
 *
 * <p>A spot that aired well away from where it was logged is moved, not missed: it ran and it is
 * billable, and the drift is kept because the client may still have a complaint about the daypart.
 *
 * @param scheduled the log line
 * @param aired the as-run line
 * @param byReference matched on the echoed order and line rather than on cart and time
 */
public record Match(LogLine scheduled, AsRun aired, boolean byReference) {

    /** How far a spot can drift and still count as having run where it was sold. */
    public static final Duration ON_TIME = Duration.ofSeconds(5 * 60);

    public LineKey key() {
        return new LineKey(scheduled.order(), scheduled.line());
    }

    public Duration drift() {
        return aired.driftFrom(scheduled.at());
    }

    public boolean onTime() {
        return drift().compareTo(ON_TIME) <= 0;
    }

    public boolean moved() {
        return !onTime();
    }

    /** Ran, but for less than it was sold for. */
    public boolean isShort() {
        return aired.length().compareTo(scheduled.length()) < 0;
    }

    /** Anything that went out is billable. Whether it is right is separate. */
    public boolean billable() {
        return aired.aired();
    }

    @Override
    public String toString() {
        String where = onTime() ? "on time" : "moved " + drift();
        return scheduled.order() + "-" + scheduled.line() + " " + where;
    }
}
