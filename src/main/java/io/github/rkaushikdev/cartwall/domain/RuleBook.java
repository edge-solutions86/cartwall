package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Everything one station's traffic needs, pinned to one date.
 *
 * <p>Built for one station and one date, read only once it exists, and the date it was built for
 * is the date every lookup inside it uses. Building a new one is cheap. Reaching into an old one
 * for a different date is the mistake this type exists to prevent, which is why nothing here takes
 * a date argument.
 *
 * @param station the logged station
 * @param on the day the book is for
 * @param dayparts the station's daypart grid
 * @param programming the station's clock grid
 * @param separation the station's separation rules
 * @param card the rate card in force on the day
 */
public record RuleBook(
        Station station,
        LocalDate on,
        DaypartGrid dayparts,
        ClockGrid programming,
        SeparationBook separation,
        RateCard card) {

    public RuleBook {
        if (!programming.station().equals(station.call())) {
            throw new RuleBookError(station.call() + " was handed " + programming.station() + "'s programming");
        }
        if (!card.station().equals(station.call())) {
            throw new RuleBookError(station.call() + " was handed " + card.station() + "'s rate card");
        }
        if (station.isSatellite()) {
            throw new RuleBookError(
                    station.call() + " carries " + station.carries().orElseThrow() + " and has no log of its own");
        }
    }

    public String call() {
        return station.call();
    }

    public Weekday weekday() {
        return Weekday.of(on);
    }

    public Clock clock(int hour) {
        return programming.clockFor(weekday(), hour);
    }

    public boolean sells(int hour) {
        return programming.sells(weekday(), hour);
    }

    public List<Integer> sellingHours() {
        return IntStream.range(0, Daypart.HOURS_IN_DAY)
                .filter(this::sells)
                .boxed()
                .toList();
    }

    /** Daypart codes covering this hour, narrowest first. */
    public List<String> daypartsFor(int hour) {
        return dayparts.covering(weekday(), AirTime.at(hour)).stream()
                .map(Daypart::code)
                .toList();
    }

    public Money rate(String daypart, ClassOfTime grade, SpotLength length) {
        return card.rate(daypart, grade, length);
    }

    public int unitsAvailable() {
        return sellingHours().stream().mapToInt(hour -> clock(hour).unitCount()).sum();
    }

    public double commercialMinutes() {
        return sellingHours().stream()
                .mapToDouble(hour -> clock(hour).commercialMinutes())
                .sum();
    }

    /** Hours whose clock carries more than the station says it will. */
    public List<Integer> overLoad() {
        return sellingHours().stream()
                .filter(hour -> !clock(hour).fitsLoad(station.commercialLoad()))
                .toList();
    }
}
