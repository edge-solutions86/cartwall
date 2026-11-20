package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Every charge on the books, which is what the lowest unit charge is measured against.
 *
 * <p>Inside the window before an election, a legally qualified candidate has to be charged the
 * lowest rate the station actually charged anybody for the same class and amount of time. Not the
 * lowest on the card: the lowest anybody paid. That makes this the one rate in the system that is
 * not a lookup, and it moves when any other order moves. A class of time nobody bought has no
 * lowest unit charge, because nothing was charged.
 */
public final class ChargeBook {

    /**
     * A class of time and the lowest anybody paid for it.
     *
     * @param grade the class of time
     * @param lowest the lowest charge in it
     */
    public record Cheapest(ClassOfTime grade, Money lowest) {}

    private final List<Charge> charges;

    public ChargeBook(List<Charge> charges) {
        this.charges = new ArrayList<>(charges);
    }

    /**
     * The lowest rate anybody was charged for this class in this window.
     *
     * <p>Empty when nothing comparable was charged. That is not zero and it is not the card: the
     * station quotes its card rate with a note saying why.
     */
    public static Optional<Money> lowestUnitCharge(
            List<Charge> charges,
            String station,
            String daypart,
            ClassOfTime grade,
            SpotLength length,
            LocalDate first,
            LocalDate last) {
        if (last.isBefore(first)) {
            throw new PoliticalError("the window runs backwards");
        }
        return charges.stream()
                .filter(charge -> charge.station().equals(station)
                        && charge.daypart().equals(daypart)
                        && charge.grade() == grade
                        && charge.length().equals(length)
                        && !charge.day().isBefore(first)
                        && !charge.day().isAfter(last))
                .map(Charge::rate)
                .min(Comparator.naturalOrder());
    }

    /** What a candidate is owed when they were charged above the lowest unit charge. */
    public static Money owedBack(Money charged, Optional<Money> luc) {
        if (luc.isEmpty() || charged.compareTo(luc.get()) <= 0) {
            return Money.ZERO;
        }
        return charged.minus(luc.get());
    }

    public List<Charge> charges() {
        return Collections.unmodifiableList(charges);
    }

    public void add(Charge charge) {
        charges.add(charge);
    }

    public Optional<Money> lowest(
            String station, String daypart, ClassOfTime grade, SpotLength length, LocalDate first, LocalDate last) {
        return lowestUnitCharge(charges, station, daypart, grade, length, first, last);
    }

    /** The lowest unit charge over a race's whole window. */
    public Optional<Money> forRace(Race race, String station, String daypart, ClassOfTime grade, SpotLength length) {
        return lowest(station, daypart, grade, length, race.windowOpens(), race.electionDay());
    }

    /** The class with the lowest charge, for a buyer who did not name one. A tie goes to the better class. */
    public Optional<Cheapest> cheapestClass(
            String station, String daypart, SpotLength length, LocalDate first, LocalDate last) {
        List<Cheapest> found = new ArrayList<>();
        for (ClassOfTime grade : ClassOfTime.values()) {
            lowest(station, daypart, grade, length, first, last)
                    .ifPresent(lowest -> found.add(new Cheapest(grade, lowest)));
        }
        return found.stream().min(Comparator.comparing(Cheapest::lowest).thenComparingInt(cheapest -> -cheapest.grade()
                .rank()));
    }

    public int size() {
        return charges.size();
    }
}
