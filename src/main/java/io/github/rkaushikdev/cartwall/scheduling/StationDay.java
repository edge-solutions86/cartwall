package io.github.rkaushikdev.cartwall.scheduling;

import java.time.LocalDate;
import java.util.Comparator;

/**
 * One station on one date: the key a board is built and shared under.
 *
 * @param call the logged station
 * @param day the date
 */
public record StationDay(String call, LocalDate day) implements Comparable<StationDay> {

    private static final Comparator<StationDay> ORDER =
            Comparator.comparing(StationDay::call).thenComparing(StationDay::day);

    @Override
    public int compareTo(StationDay other) {
        return ORDER.compare(this, other);
    }
}
