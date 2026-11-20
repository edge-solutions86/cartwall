package io.github.rkaushikdev.cartwall.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * One rule about which clock runs where, laid on top of the ones before.
 *
 * <p>A preemption is an assignment with no clock. The hour still exists and the log still has to
 * account for it; there is simply no local inventory in it. That is not an empty clock, which is a
 * station error. A preemption is Friday night football.
 *
 * @param label what the programme director called it
 * @param days the weekdays it covers
 * @param firstHour the first hour, inclusive
 * @param lastHour the last hour, exclusive
 * @param clock the clock it runs; empty for a preemption
 * @param source where the programming comes from
 */
public record Assignment(String label, Set<Weekday> days, int firstHour, int lastHour, String clock, Source source) {

    public Assignment {
        days = days.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(days));
        if (label == null || label.isEmpty()) {
            throw new ProgrammingError("an assignment needs a label");
        }
        if (days.isEmpty()) {
            throw new ProgrammingError(label + " runs on no days");
        }
        if (firstHour < 0 || firstHour >= lastHour || lastHour > Daypart.HOURS_IN_DAY) {
            throw new ProgrammingError(
                    label + " runs " + firstHour + " to " + lastHour + ", which is not an hour span");
        }
        if (source.sells() && clock.isEmpty()) {
            throw new ProgrammingError(label + " sells but names no clock");
        }
        if (!source.sells() && !clock.isEmpty()) {
            throw new ProgrammingError(label + " is preempted but names clock " + clock);
        }
    }

    /** A local clock over the days as written, such as {@code M-F}. */
    public static Assignment build(String label, String days, int firstHour, int lastHour, String clock) {
        return build(label, days, firstHour, lastHour, clock, Source.LOCAL);
    }

    public static Assignment build(
            String label, String days, int firstHour, int lastHour, String clock, Source source) {
        return new Assignment(label, Weekday.parseDays(days), firstHour, lastHour, clock, source);
    }

    /** The hours come off the grid: a ballgame, a remote, a special. */
    public static Assignment preempt(String label, String days, int firstHour, int lastHour) {
        return build(label, days, firstHour, lastHour, "", Source.PREEMPTED);
    }

    public boolean covers(Weekday day, int hour) {
        return days.contains(day) && firstHour <= hour && hour < lastHour;
    }

    /** Hours a week this assignment covers. */
    public int hours() {
        return (lastHour - firstHour) * days.size();
    }

    @Override
    public String toString() {
        String what = clock.isEmpty() ? source.value() : clock;
        return label + " " + Weekday.showDays(days) + " " + firstHour + "-" + lastHour + " " + what;
    }
}
