package io.github.rkaushikdev.cartwall.scheduling;

import java.time.LocalDate;

/**
 * One spot, and where it ended up.
 *
 * @param day the date
 * @param hour the hour
 * @param number the break number
 * @param cart the cart it plays
 */
public record Landed(LocalDate day, int hour, int number, String cart) {

    @Override
    public String toString() {
        return day + " hour " + hour + " break " + number;
    }
}
