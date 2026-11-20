package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.BroadcastWeek;
import io.github.rkaushikdev.cartwall.Money;
import java.util.List;

/**
 * One thing being bought, on one station: a daypart, a class, a length and a rate, over a flight.
 *
 * @param number the line's number on the order
 * @param station the station it was bought on
 * @param daypart the daypart code
 * @param grade the class of time
 * @param length the sold length
 * @param rate the agreed rate per spot
 * @param flight when it runs and how often
 * @param carts carts eligible for this line; empty means production has not dubbed yet
 */
public record Line(
        int number,
        String station,
        String daypart,
        ClassOfTime grade,
        SpotLength length,
        Money rate,
        Flight flight,
        List<String> carts) {

    public Line {
        carts = List.copyOf(carts);
        if (number < 1) {
            throw new OrderError("line " + number + " is not a line number");
        }
        if (station == null || station.isEmpty()) {
            throw new OrderError("line " + number + " names no station");
        }
        if (daypart == null || daypart.isEmpty()) {
            throw new OrderError("line " + number + " names no daypart");
        }
        if (rate.isNegative()) {
            throw new OrderError("line " + number + " is priced at " + rate);
        }
    }

    /** A line with no copy dubbed yet. */
    public Line(
            int number,
            String station,
            String daypart,
            ClassOfTime grade,
            SpotLength length,
            Money rate,
            Flight flight) {
        this(number, station, daypart, grade, length, rate, flight, List.of());
    }

    public int spots() {
        return flight.spots();
    }

    public Money value() {
        return rate.times(spots());
    }

    public Money valueIn(BroadcastWeek week) {
        return rate.times(flight.countIn(week));
    }

    public boolean hasCopy() {
        return !carts.isEmpty();
    }

    public Line withCarts(List<String> carts) {
        return new Line(number, station, daypart, grade, length, rate, flight, carts);
    }

    @Override
    public String toString() {
        return number + " " + station + " " + daypart + " " + length + " " + flight;
    }
}
