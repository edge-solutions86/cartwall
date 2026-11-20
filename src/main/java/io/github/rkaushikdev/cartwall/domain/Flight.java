package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.BroadcastCalendar;
import io.github.rkaushikdev.cartwall.BroadcastWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * When a line runs, on which weekdays, and how many spots a week.
 *
 * <p>A line is sold as spots per week, but a flight almost never starts on a Monday. A schedule of
 * twelve a week that starts on a Thursday has three days of that first week. The house rounds the
 * stub week down and says so on the order, and a stub week that would round to nothing still gets
 * one spot: zero in a week the client is paying for is worse than being a spot generous.
 *
 * @param first the first day it may air
 * @param last the last day it may air
 * @param days the weekdays it airs on
 * @param perWeek spots in a whole week
 */
public record Flight(LocalDate first, LocalDate last, Set<Weekday> days, int perWeek) {

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM/dd", Locale.ROOT);

    public Flight {
        days = days.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(days));
        if (last.isBefore(first)) {
            throw new OrderError("a flight from " + first + " to " + last + " runs backwards");
        }
        if (days.isEmpty()) {
            throw new OrderError("a flight runs on no days");
        }
        if (perWeek < 1) {
            throw new OrderError(perWeek + " a week is not a schedule");
        }
    }

    /** A flight with its days written the way the order writes them, such as {@code M-F}. */
    public static Flight build(LocalDate first, LocalDate last, String days, int perWeek) {
        return new Flight(first, last, Weekday.parseDays(days), perWeek);
    }

    /** Every broadcast week the flight touches. */
    public List<BroadcastWeek> weeks() {
        return BroadcastCalendar.weeksBetween(first, last);
    }

    public boolean runsOn(LocalDate day) {
        return !day.isBefore(first) && !day.isAfter(last) && days.contains(Weekday.of(day));
    }

    /** Every date this flight may put a spot on, in order. */
    public List<LocalDate> airDays() {
        List<LocalDate> found = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            if (days.contains(Weekday.of(day))) {
                found.add(day);
            }
        }
        return List.copyOf(found);
    }

    public List<LocalDate> daysIn(BroadcastWeek week) {
        return week.days().stream().filter(this::runsOn).toList();
    }

    /** How many air days a whole week of this flight has. */
    public int fullWeekDays() {
        return days.size();
    }

    /**
     * Spots ordered in {@code week}, prorating a stub week down.
     *
     * <p>A whole week gets the full weekly count. A stub week gets that count scaled by the air
     * days it actually has, rounded down, floored at one so a paid week is never empty.
     */
    public int countIn(BroadcastWeek week) {
        int available = daysIn(week).size();
        if (available == 0) {
            return 0;
        }
        if (available == fullWeekDays()) {
            return perWeek;
        }
        return Math.max(1, perWeek * available / fullWeekDays());
    }

    /**
     * One week of a flight and the spots ordered in it.
     *
     * @param week the broadcast week
     * @param count spots ordered in it
     */
    public record WeekCount(BroadcastWeek week, int count) {}

    public List<WeekCount> weeklyCounts() {
        return weeks().stream().map(week -> new WeekCount(week, countIn(week))).toList();
    }

    /** Every spot the flight orders. */
    public int spots() {
        return weeklyCounts().stream().mapToInt(WeekCount::count).sum();
    }

    @Override
    public String toString() {
        return MONTH_DAY.format(first) + "-" + MONTH_DAY.format(last) + " " + Weekday.showDays(days) + " " + perWeek
                + "x";
    }
}
