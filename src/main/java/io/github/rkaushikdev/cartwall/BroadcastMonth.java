package io.github.rkaushikdev.cartwall;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Four or five whole broadcast weeks, named for a wall calendar month.
 *
 * @param year the broadcast year
 * @param month 1 to 12
 */
public record BroadcastMonth(int year, int month) implements Comparable<BroadcastMonth> {

    private static final Comparator<BroadcastMonth> ORDER =
            Comparator.comparingInt(BroadcastMonth::year).thenComparingInt(BroadcastMonth::month);

    public BroadcastMonth {
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month " + month + " is not a month");
        }
    }

    /** The broadcast month holding {@code day}. */
    public static BroadcastMonth of(LocalDate day) {
        int year = BroadcastCalendar.yearOf(day);
        for (int month = 1; month <= 12; month++) {
            if (!day.isAfter(BroadcastCalendar.monthBounds(year, month)[1])) {
                return new BroadcastMonth(year, month);
            }
        }
        throw new IllegalStateException(day + " fell out of broadcast " + year);
    }

    /** {@code 2025-08}, which is how the sales side writes it on an order. */
    public static BroadcastMonth parse(String text) {
        int dash = text.indexOf('-');
        if (dash < 0 || dash == text.length() - 1) {
            throw new IllegalArgumentException("'" + text + "' is not a broadcast month");
        }
        return new BroadcastMonth(
                Integer.parseInt(text.substring(0, dash)), Integer.parseInt(text.substring(dash + 1)));
    }

    /** The Monday the month opens on. */
    public LocalDate start() {
        return BroadcastCalendar.monthBounds(year, month)[0];
    }

    /** The Sunday the month closes on. */
    public LocalDate end() {
        return BroadcastCalendar.monthBounds(year, month)[1];
    }

    public int weekCount() {
        return BroadcastCalendar.weekCounts(year)[month - 1];
    }

    /** The weeks of the month, in order. */
    public List<BroadcastWeek> weeks() {
        List<BroadcastWeek> weeks = new ArrayList<>();
        BroadcastWeek week = new BroadcastWeek(start());
        for (int index = 0; index < weekCount(); index++) {
            weeks.add(week);
            week = week.shift(1);
        }
        return List.copyOf(weeks);
    }

    public BroadcastMonth next() {
        return month == 12 ? new BroadcastMonth(year + 1, 1) : new BroadcastMonth(year, month + 1);
    }

    public BroadcastMonth previous() {
        return month == 1 ? new BroadcastMonth(year - 1, 12) : new BroadcastMonth(year, month - 1);
    }

    public boolean contains(LocalDate day) {
        return !day.isBefore(start()) && !day.isAfter(end());
    }

    @Override
    public int compareTo(BroadcastMonth other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return "%04d-%02d".formatted(year, month);
    }
}
