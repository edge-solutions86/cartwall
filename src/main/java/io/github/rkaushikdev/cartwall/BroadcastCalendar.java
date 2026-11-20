package io.github.rkaushikdev.cartwall;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The calendar the traffic department keeps, which is not the wall calendar.
 *
 * <p>Radio bills on the broadcast calendar. Weeks run Monday through Sunday, a broadcast year
 * opens on the Monday of the week that holds January 1, and the three months inside a quarter run
 * four weeks, four weeks, five weeks. Every month therefore opens on a Monday and closes on a
 * Sunday. Broadcast August 2025 ends on the 24th.
 *
 * <p>Two consequences the rest of the package leans on. A flight sold "for March" is a whole
 * number of weeks, so nothing downstream ever prorates a partial week. And roughly one year in six
 * runs 53 weeks; the extra week hangs on December, so fourth quarter is 14 weeks in those years.
 */
public final class BroadcastCalendar {

    public static final int WEEKS_IN_QUARTER = 13;

    /** Weeks per month within a quarter, before December picks up a week 53. */
    static final int[] MONTH_PATTERN = {4, 4, 5};

    private BroadcastCalendar() {}

    /** The Monday on or before {@code day}. */
    public static LocalDate weekStart(LocalDate day) {
        return day.minusDays(day.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
    }

    /**
     * The Monday the broadcast year opens on.
     *
     * <p>It is the Monday of the wall calendar week holding January 1, so it can sit in the
     * previous December. Broadcast 2025 opened on 2024-12-30.
     */
    public static LocalDate yearStart(int year) {
        return weekStart(LocalDate.of(year, 1, 1));
    }

    /** 52 or 53. Anything else means the arithmetic broke. */
    public static int weeksInYear(int year) {
        return (int) (ChronoUnit.DAYS.between(yearStart(year), yearStart(year + 1)) / 7);
    }

    /**
     * Which broadcast year {@code day} falls in.
     *
     * <p>The last days of December usually belong to the next broadcast year, and the first days
     * of January usually belong to the previous one.
     */
    public static int yearOf(LocalDate day) {
        int year = day.getYear();
        if (day.isBefore(yearStart(year))) {
            return year - 1;
        }
        if (!day.isBefore(yearStart(year + 1))) {
            return year + 1;
        }
        return year;
    }

    /** Every broadcast week touched by the inclusive span {@code first} to {@code last}. */
    public static List<BroadcastWeek> weeksBetween(LocalDate first, LocalDate last) {
        if (last.isBefore(first)) {
            throw new IllegalArgumentException("the span runs backwards");
        }
        List<BroadcastWeek> weeks = new ArrayList<>();
        BroadcastWeek week = BroadcastWeek.of(first);
        while (!week.start().isAfter(last)) {
            weeks.add(week);
            week = week.shift(1);
        }
        return List.copyOf(weeks);
    }

    /** Weeks in each of the twelve months, January first. */
    static int[] weekCounts(int year) {
        int[] counts = new int[12];
        for (int month = 1; month <= 12; month++) {
            counts[month - 1] = MONTH_PATTERN[(month - 1) % 3];
        }
        counts[11] += weeksInYear(year) - 52;
        return counts;
    }

    /** The first and last day of a broadcast month. */
    static LocalDate[] monthBounds(int year, int month) {
        int[] counts = weekCounts(year);
        int before = 0;
        for (int index = 0; index < month - 1; index++) {
            before += counts[index];
        }
        LocalDate opens = yearStart(year).plusWeeks(before);
        return new LocalDate[] {opens, opens.plusWeeks(counts[month - 1]).minusDays(1)};
    }
}
