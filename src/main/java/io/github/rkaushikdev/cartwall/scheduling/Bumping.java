package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Taking a unit off somebody who has one so somebody else can have it.
 *
 * <p>This is the part of traffic that is a promise rather than an algorithm. Fixed is not bumped.
 * Preemptible with notice can be bumped if the client was told at least a fortnight ago, which is
 * why everything here takes today's date. Immediately preemptible can be bumped the morning of.
 * Only a strictly higher class may bump: equal class does not, however much more money is on the
 * incoming order, because picking between two clients sold the same promise on price is exactly
 * what the classes exist to stop.
 */
public final class Bumping {

    private Bumping() {}

    /** Whether {@code incoming} is entitled to take a unit off {@code sitting}. */
    public static boolean mayBump(ClassOfTime sitting, ClassOfTime incoming, LocalDate air, LocalDate today) {
        if (incoming.rank() <= sitting.rank()) {
            return false;
        }
        if (!sitting.mayBePreempted()) {
            return false;
        }
        return ChronoUnit.DAYS.between(today, air) >= sitting.noticeDays();
    }

    /** What in this break the ask could take, cheapest promise first, longest first within it. */
    public static List<Placement> preemptible(Avail avail, Ask ask, LocalDate today) {
        return avail.placements().stream()
                .filter(placement -> !placement.fixed() && mayBump(placement.grade(), ask.grade(), avail.day(), today))
                .sorted(Comparator.comparingInt(
                                (Placement placement) -> placement.grade().rank())
                        .thenComparingInt(placement -> -placement.length().units()))
                .toList();
    }

    /**
     * The fewest spots to move so the ask fits, or nothing if it cannot be done.
     *
     * <p>Cheapest promise first, and inside a class the longest spot first, because one sixty
     * coming off is one client to apologise to instead of two thirties.
     */
    public static Optional<List<Placement>> enoughToBump(Avail avail, Ask ask, LocalDate today) {
        if (avail.accepts(ask.length())) {
            return Optional.of(List.of());
        }
        List<Placement> taken = new ArrayList<>();
        int units = avail.freeUnits();
        Duration seconds = avail.freeLength();
        for (Placement placement : preemptible(avail, ask, today)) {
            if (units >= ask.length().units() && seconds.compareTo(ask.length().air()) >= 0) {
                break;
            }
            taken.add(placement);
            units += placement.length().units();
            seconds = seconds.plus(placement.length().air());
        }
        if (units < ask.length().units() || seconds.compareTo(ask.length().air()) < 0) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(taken));
    }

    /** Make room in {@code avail} for {@code ask} and put it in. Returns what came off. */
    public static List<Bumped> bumpInto(Avail avail, Ask ask, LocalDate today) {
        return bumpInto(avail, ask, today, "", Optional.empty());
    }

    /** Make room in {@code avail} for {@code ask} playing {@code cart}. Returns what came off. */
    public static List<Bumped> bumpInto(Avail avail, Ask ask, LocalDate today, String cart) {
        return bumpInto(avail, ask, today, cart, Optional.empty());
    }

    /** Make room in {@code avail} for {@code ask}, queueing what came off for the makegood run. */
    public static List<Bumped> bumpInto(Avail avail, Ask ask, LocalDate today, String cart, BumpQueue queue) {
        return bumpInto(avail, ask, today, cart, Optional.of(queue));
    }

    static List<Bumped> bumpInto(Avail avail, Ask ask, LocalDate today, String cart, Optional<BumpQueue> queue) {
        if (avail.day().isBefore(today)) {
            throw new BumpError(
                    avail.day() + " has already aired and cannot be rescheduled",
                    Details.of("day", avail.day().toString(), "today", today.toString()));
        }
        List<Placement> taken = enoughToBump(avail, ask, today)
                .orElseThrow(() -> new NothingToBump(
                        "nothing in " + avail.station() + " hour " + avail.hour() + " break " + avail.number() + " on "
                                + avail.day() + " may be moved for a "
                                + ask.grade().value() + " spot",
                        Details.of(
                                "station", avail.station(),
                                "hour", avail.hour(),
                                "number", avail.number(),
                                "grade", ask.grade().value())));
        List<Bumped> removed = new ArrayList<>();
        for (Placement placement : taken) {
            avail.drop(placement.order(), placement.line());
            Bumped bumped = new Bumped(placement, avail.day(), avail.hour(), avail.number(), ask.order(), ask.line());
            removed.add(bumped);
            queue.ifPresent(waiting -> waiting.add(bumped));
        }
        avail.add(new Placement(
                ask.order(),
                ask.line(),
                ask.advertiser().code(),
                ask.length(),
                ask.grade(),
                cart.isEmpty() ? ask.cart() : cart));
        return List.copyOf(removed);
    }
}
