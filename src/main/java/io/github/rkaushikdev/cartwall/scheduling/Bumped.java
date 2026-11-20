package io.github.rkaushikdev.cartwall.scheduling;

import java.time.LocalDate;

/**
 * One spot taken off the air, and who took it.
 *
 * @param placement the spot that came off
 * @param day the date it was on
 * @param hour the hour
 * @param number the break number
 * @param byOrder the order that took the unit
 * @param byLine the line that took the unit
 */
public record Bumped(Placement placement, LocalDate day, int hour, int number, String byOrder, int byLine) {

    public LineKey key() {
        return placement.key();
    }

    @Override
    public String toString() {
        return placement.order() + "-" + placement.line() + " out of " + day + " hour " + hour + " break " + number
                + " for " + byOrder + "-" + byLine;
    }
}
