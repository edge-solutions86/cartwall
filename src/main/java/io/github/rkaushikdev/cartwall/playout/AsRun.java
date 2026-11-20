package io.github.rkaushikdev.cartwall.playout;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.scheduling.LineKey;
import java.time.LocalDate;
import java.util.Optional;

/**
 * One line of the as-run: what played, when, and for how long.
 *
 * <p>What the as-run tells us that the log cannot: when it really ran (automation drifts), whether
 * it ran at all (a cart that would not load is not billable), and how long it ran for (a thirty
 * that ran nineteen seconds did not run).
 *
 * @param day the date
 * @param at when it actually ran
 * @param cart the cart that played
 * @param title what the automation calls it
 * @param length how long it ran
 * @param status what the automation says happened
 * @param reference the order and line the log carried, when the automation echoes it back
 */
public record AsRun(
        LocalDate day, AirTime at, String cart, String title, Duration length, Played status, String reference) {

    public boolean aired() {
        return status.aired();
    }

    /**
     * The order and line, when the reference is one we wrote.
     *
     * <p>Split on the last dash, not the first. An order number has one of its own, so {@code
     * 25-1044-1} split forwards gives order {@code 25}.
     */
    public Optional<LineKey> key() {
        int dash = reference.lastIndexOf('-');
        if (dash <= 0) {
            return Optional.empty();
        }
        String line = reference.substring(dash + 1);
        if (line.isEmpty() || !line.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        return Optional.of(new LineKey(reference.substring(0, dash), Integer.parseInt(line)));
    }

    /** How far off the logged time this ran. Never negative. */
    public Duration driftFrom(AirTime scheduled) {
        return at.isBefore(scheduled) ? scheduled.minus(at) : at.minus(scheduled);
    }

    @Override
    public String toString() {
        return at.twelveHour() + " " + cart + " " + status.value();
    }
}
