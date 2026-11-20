package io.github.rkaushikdev.cartwall.domain;

import java.util.Comparator;

/**
 * One hour of one weekday: a cell of the 168 that make up the selling week.
 *
 * @param day the weekday
 * @param hour 0 to 23
 */
public record Cell(Weekday day, int hour) implements Comparable<Cell> {

    private static final Comparator<Cell> ORDER =
            Comparator.comparing(Cell::day).thenComparingInt(Cell::hour);

    @Override
    public int compareTo(Cell other) {
        return ORDER.compare(this, other);
    }
}
