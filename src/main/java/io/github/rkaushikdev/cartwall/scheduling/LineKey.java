package io.github.rkaushikdev.cartwall.scheduling;

import java.util.Comparator;

/**
 * One line of one order, which is what a spot on the board answers to.
 *
 * @param order the order number
 * @param line the line number on it
 */
public record LineKey(String order, int line) implements Comparable<LineKey> {

    private static final Comparator<LineKey> ORDER =
            Comparator.comparing(LineKey::order).thenComparingInt(LineKey::line);

    @Override
    public int compareTo(LineKey other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return order + "-" + line;
    }
}
