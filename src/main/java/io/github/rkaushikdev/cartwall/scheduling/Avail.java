package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.domain.Break;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One break on one date, with what is in it.
 *
 * <p>Both capacities a break has are checked on every placement, because either can bind first:
 * a three minute break of six units is full on units after six thirties, full on seconds after
 * three sixties, and after two sixties and a thirty still has room for one more thirty by both
 * counts. Spots sit in the order they were placed.
 */
public final class Avail {

    private final String station;
    private final LocalDate day;
    private final int hour;
    private final int number;
    private final Duration position;
    private final Duration capacity;
    private final int units;
    private final List<Placement> placements = new ArrayList<>();

    public Avail(String station, LocalDate day, int hour, int number, Duration position, Duration capacity, int units) {
        this.station = station;
        this.day = day;
        this.hour = hour;
        this.number = number;
        this.position = position;
        this.capacity = capacity;
        this.units = units;
    }

    /** The avail a clock's break makes in one hour of one date. */
    public static Avail of(String station, LocalDate day, int hour, Break stopset) {
        return new Avail(station, day, hour, stopset.number(), stopset.position(), stopset.length(), stopset.units());
    }

    public String station() {
        return station;
    }

    public LocalDate day() {
        return day;
    }

    public int hour() {
        return hour;
    }

    public int number() {
        return number;
    }

    public Duration position() {
        return position;
    }

    public Duration capacity() {
        return capacity;
    }

    public int units() {
        return units;
    }

    /** What is in the break, in the order it was placed. */
    public List<Placement> placements() {
        return Collections.unmodifiableList(placements);
    }

    public AirTime startsAt() {
        return AirTime.at(hour).plus(position);
    }

    public int usedUnits() {
        return placements.stream()
                .mapToInt(placement -> placement.length().units())
                .sum();
    }

    public Duration usedLength() {
        return Duration.total(
                placements.stream().map(placement -> placement.length().air()).toList());
    }

    public int freeUnits() {
        return units - usedUnits();
    }

    public Duration freeLength() {
        return capacity.minus(usedLength());
    }

    public boolean isFull() {
        return freeUnits() <= 0 || freeLength().isZero();
    }

    public boolean accepts(SpotLength length) {
        return length.units() <= freeUnits() && length.air().compareTo(freeLength()) <= 0;
    }

    public boolean holds(String order, int line) {
        LineKey key = new LineKey(order, line);
        return placements.stream().anyMatch(placement -> placement.key().equals(key));
    }

    /** Put a spot in at the back, refusing when either capacity would be passed. */
    public void add(Placement placement) {
        if (!accepts(placement.length())) {
            throw new BreakFull(
                    station + " " + day + " hour " + hour + " break " + number + " will not take a "
                            + placement.length(),
                    Details.of(
                            "station", station,
                            "hour", hour,
                            "number", number,
                            "free_units", freeUnits(),
                            "free_seconds", freeLength().seconds()));
        }
        placements.add(placement);
    }

    /** Take one line's spot out of the break. */
    public Placement drop(String order, int line) {
        LineKey key = new LineKey(order, line);
        for (int index = 0; index < placements.size(); index++) {
            if (placements.get(index).key().equals(key)) {
                return placements.remove(index);
            }
        }
        throw new InventoryError(order + "-" + line + " is not in " + station + " hour " + hour + " break " + number);
    }

    /** Each spot with the time it will hit the air, in break order. */
    public List<Timed> airTimes() {
        AirTime cursor = startsAt();
        List<Timed> timed = new ArrayList<>(placements.size());
        for (Placement placement : placements) {
            timed.add(new Timed(placement, cursor));
            cursor = cursor.plus(placement.length().air());
        }
        return List.copyOf(timed);
    }

    public int size() {
        return placements.size();
    }

    @Override
    public String toString() {
        return station + " " + day + " " + startsAt() + " break " + number;
    }
}
