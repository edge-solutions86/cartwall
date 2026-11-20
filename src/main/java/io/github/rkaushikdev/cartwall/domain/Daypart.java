package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.AirTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Whole hours on whole days, named and sellable: the unit air is priced and sold in.
 *
 * <p>Nothing here deals in part hours, because nothing in the business does. The week is 168
 * cells and a daypart is a subset of them, which turns overlap and containment into set algebra.
 *
 * @param code the code the card and the order use
 * @param name what the sales side calls it
 * @param days the weekdays it runs on
 * @param firstHour the first hour, inclusive
 * @param lastHour the last hour, exclusive, so 7p to midnight is 19 to 24
 */
public record Daypart(String code, String name, Set<Weekday> days, int firstHour, int lastHour) {

    public static final int HOURS_IN_DAY = 24;
    public static final int DAYS_IN_WEEK = 7;
    public static final int CELLS_IN_WEEK = HOURS_IN_DAY * DAYS_IN_WEEK;

    public Daypart {
        days = days.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(days));
        if (code == null || code.isEmpty()) {
            throw new DaypartError("a daypart needs a code");
        }
        if (days.isEmpty()) {
            throw new DaypartError(code + " runs on no days");
        }
        if (firstHour < 0 || firstHour >= HOURS_IN_DAY) {
            throw new DaypartError(code + " starts at hour " + firstHour);
        }
        if (lastHour <= firstHour || lastHour > HOURS_IN_DAY) {
            throw new DaypartError(code + " ends at hour " + lastHour + ", which is not after it");
        }
    }

    /** A daypart from the days the way the card writes them, such as {@code M-F}. */
    public static Daypart build(String code, String name, String days, int firstHour, int lastHour) {
        return new Daypart(code, name, Weekday.parseDays(days), firstHour, lastHour);
    }

    public int hoursADay() {
        return lastHour - firstHour;
    }

    /** Every hour of the week this daypart sells, in week order. */
    public Set<Cell> cells() {
        Set<Cell> cells = new TreeSet<>();
        for (Weekday day : days) {
            for (int hour = firstHour; hour < lastHour; hour++) {
                cells.add(new Cell(day, hour));
            }
        }
        return Collections.unmodifiableSet(cells);
    }

    public boolean holds(Weekday day, AirTime at) {
        return days.contains(day) && firstHour <= at.hour() && at.hour() < lastHour;
    }

    public boolean overlaps(Daypart other) {
        Set<Cell> shared = new TreeSet<>(cells());
        shared.retainAll(other.cells());
        return !shared.isEmpty();
    }

    public boolean containsDaypart(Daypart other) {
        return cells().containsAll(other.cells());
    }

    /** {@code 6a-10a}, {@code 7p-mid}, {@code 11a-noon}. */
    public String span() {
        return hourLabel(firstHour) + "-" + hourLabel(lastHour);
    }

    @Override
    public String toString() {
        return Weekday.showDays(days) + " " + span();
    }

    private static String hourLabel(int hour) {
        if (hour == 0 || hour == 24) {
            return "mid";
        }
        if (hour == 12) {
            return "noon";
        }
        if (hour < 12) {
            return hour + "a";
        }
        return (hour - 12) + "p";
    }
}
