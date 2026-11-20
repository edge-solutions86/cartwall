package io.github.rkaushikdev.cartwall.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A day of the broadcast week, Monday first, with the short name the sales side writes.
 *
 * <p>Sets of days are written {@code M-F}, {@code Sa}, {@code M-Su}, {@code M,W,F} and {@code
 * Sa,Su}, and {@link #parseDays} and {@link #showDays} go both ways between that and a set.
 */
public enum Weekday {
    MON("M"),
    TUE("Tu"),
    WED("W"),
    THU("Th"),
    FRI("F"),
    SAT("Sa"),
    SUN("Su");

    private static final Pattern RANGE = Pattern.compile("^([A-Za-z]{1,2})-([A-Za-z]{1,2})$");

    private final String shortName;

    Weekday(String shortName) {
        this.shortName = shortName;
    }

    /** {@code M}, {@code Tu}, {@code Sa}: how the day is written on an order. */
    public String shortName() {
        return shortName;
    }

    /** The broadcast weekday of a date. */
    public static Weekday of(LocalDate day) {
        return values()[day.getDayOfWeek().getValue() - 1];
    }

    /**
     * {@code M-F}, {@code Sa}, {@code M-Su}, {@code M,W,F} and {@code Sa,Su} are the shapes used.
     *
     * <p>A range runs forward through the week and does not wrap, so {@code Sa-Su} is the weekend
     * and {@code Su-Sa} is an error rather than the whole week by another name.
     */
    public static Set<Weekday> parseDays(String text) {
        String cleaned = text.replace(" ", "");
        if (cleaned.isEmpty()) {
            throw new DaypartError("no days given");
        }
        EnumSet<Weekday> days = EnumSet.noneOf(Weekday.class);
        for (String piece : cleaned.split(",", -1)) {
            Matcher span = RANGE.matcher(piece);
            if (span.matches()) {
                Weekday first = lookup(span.group(1));
                Weekday last = lookup(span.group(2));
                if (last.compareTo(first) < 0) {
                    throw new DaypartError("'" + piece + "' runs backwards through the week");
                }
                days.addAll(EnumSet.range(first, last));
            } else {
                days.add(lookup(piece));
            }
        }
        return Collections.unmodifiableSet(days);
    }

    /** The other direction, collapsing runs back into ranges. */
    public static String showDays(Set<Weekday> days) {
        if (days.isEmpty()) {
            return "";
        }
        List<Weekday> ordered = new ArrayList<>(EnumSet.copyOf(days));
        List<String> pieces = new ArrayList<>();
        Weekday runStart = ordered.get(0);
        Weekday previous = runStart;
        for (Weekday day : ordered.subList(1, ordered.size())) {
            if (day.ordinal() == previous.ordinal() + 1) {
                previous = day;
                continue;
            }
            pieces.add(showRun(runStart, previous));
            runStart = day;
            previous = day;
        }
        pieces.add(showRun(runStart, previous));
        return String.join(",", pieces);
    }

    /** An immutable set of the days given, kept in week order. */
    public static Set<Weekday> setOf(Weekday... days) {
        EnumSet<Weekday> set = EnumSet.noneOf(Weekday.class);
        Collections.addAll(set, days);
        return Collections.unmodifiableSet(set);
    }

    private static Weekday lookup(String text) {
        for (Weekday day : values()) {
            if (day.shortName.toUpperCase(Locale.ROOT).equals(text.toUpperCase(Locale.ROOT))) {
                return day;
            }
        }
        throw new DaypartError("'" + text + "' is not a day of the week");
    }

    private static String showRun(Weekday first, Weekday last) {
        if (first == last) {
            return first.shortName;
        }
        if (last.ordinal() == first.ordinal() + 1) {
            return first.shortName + "," + last.shortName;
        }
        return first.shortName + "-" + last.shortName;
    }
}
