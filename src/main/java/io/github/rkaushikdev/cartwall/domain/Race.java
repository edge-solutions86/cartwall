package io.github.rkaushikdev.cartwall.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * One election, on one date, with the window it opens.
 *
 * @param office the office being contested
 * @param kind primary, runoff, general or special
 * @param electionDay the day of the vote
 */
public record Race(String office, Election kind, LocalDate electionDay) {

    private static final DateTimeFormatter PRINTED = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ROOT);

    public Race {
        if (office == null || office.isEmpty()) {
            throw new PoliticalError("a race needs an office");
        }
    }

    public LocalDate windowOpens() {
        return electionDay.minusDays(kind.windowDays());
    }

    /** Inclusive at both ends. Election day itself is inside it. */
    public boolean inWindow(LocalDate day) {
        return !day.isBefore(windowOpens()) && !day.isAfter(electionDay);
    }

    public long daysLeft(LocalDate day) {
        return ChronoUnit.DAYS.between(day, electionDay);
    }

    @Override
    public String toString() {
        return office + " " + kind.value() + " " + PRINTED.format(electionDay);
    }
}
