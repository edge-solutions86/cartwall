package io.github.rkaushikdev.cartwall;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Thirteen weeks, except the fourth quarter of a 53 week year.
 *
 * @param year the broadcast year
 * @param quarter 1 to 4
 */
public record BroadcastQuarter(int year, int quarter) implements Comparable<BroadcastQuarter> {

    private static final Comparator<BroadcastQuarter> ORDER =
            Comparator.comparingInt(BroadcastQuarter::year).thenComparingInt(BroadcastQuarter::quarter);

    public BroadcastQuarter {
        if (quarter < 1 || quarter > 4) {
            throw new IllegalArgumentException("quarter " + quarter + " is not a quarter");
        }
    }

    /** The broadcast quarter holding {@code day}. */
    public static BroadcastQuarter of(LocalDate day) {
        BroadcastMonth month = BroadcastMonth.of(day);
        return new BroadcastQuarter(month.year(), (month.month() - 1) / 3 + 1);
    }

    /** The three months, in order. */
    public List<BroadcastMonth> months() {
        int first = (quarter - 1) * 3 + 1;
        return IntStream.range(first, first + 3)
                .mapToObj(month -> new BroadcastMonth(year, month))
                .toList();
    }

    public LocalDate start() {
        return new BroadcastMonth(year, (quarter - 1) * 3 + 1).start();
    }

    public LocalDate end() {
        return new BroadcastMonth(year, quarter * 3).end();
    }

    public int weekCount() {
        return months().stream().mapToInt(BroadcastMonth::weekCount).sum();
    }

    @Override
    public int compareTo(BroadcastQuarter other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return "%04dQ%d".formatted(year, quarter);
    }
}
