package io.github.rkaushikdev.cartwall.reports;

import java.time.LocalDate;

/**
 * One political spot that ran, as the file has to record it.
 *
 * @param day the date it ran
 * @param at the time off the as-run
 * @param station the station
 */
public record Aired(LocalDate day, String at, String station) {

    @Override
    public String toString() {
        return Ran.SWORN_DATE.format(day) + " " + at + " " + station;
    }
}
