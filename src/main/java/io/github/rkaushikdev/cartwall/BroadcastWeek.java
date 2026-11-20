package io.github.rkaushikdev.cartwall;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/**
 * Monday through Sunday. The unit everything else on the broadcast calendar is built out of.
 *
 * @param start the Monday the week opens on
 */
public record BroadcastWeek(LocalDate start) implements Comparable<BroadcastWeek> {

    public BroadcastWeek {
        if (start.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("a broadcast week starts on a Monday, not a "
                    + start.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        }
    }

    /** The week holding {@code day}. */
    public static BroadcastWeek of(LocalDate day) {
        return new BroadcastWeek(BroadcastCalendar.weekStart(day));
    }

    /** The Sunday the week closes on. */
    public LocalDate end() {
        return start.plusDays(6);
    }

    /** The seven days, Monday first. */
    public List<LocalDate> days() {
        return IntStream.range(0, 7).mapToObj(start::plusDays).toList();
    }

    /** The week {@code weeks} later, or earlier when negative. */
    public BroadcastWeek shift(int weeks) {
        return new BroadcastWeek(start.plusWeeks(weeks));
    }

    public boolean contains(LocalDate day) {
        return !day.isBefore(start) && !day.isAfter(end());
    }

    @Override
    public int compareTo(BroadcastWeek other) {
        return start.compareTo(other.start);
    }

    @Override
    public String toString() {
        return "week of " + start;
    }
}
