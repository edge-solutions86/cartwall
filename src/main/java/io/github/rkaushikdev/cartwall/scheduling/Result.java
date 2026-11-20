package io.github.rkaushikdev.cartwall.scheduling;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** What one line's week came to: where each spot landed, and every one that missed. */
public final class Result {

    private final Ask ask;
    private final List<Landed> landed = new ArrayList<>();
    private final List<Miss> missed = new ArrayList<>();

    public Result(Ask ask) {
        this.ask = ask;
    }

    public Ask ask() {
        return ask;
    }

    /** Every spot placed, by day, hour and break. */
    public List<Landed> landed() {
        return Collections.unmodifiableList(landed);
    }

    public List<Miss> missed() {
        return Collections.unmodifiableList(missed);
    }

    public int placed() {
        return landed.size();
    }

    public int shortBy() {
        return missed.size();
    }

    public boolean isComplete() {
        return missed.isEmpty();
    }

    /** Spots placed on each day, in date order. */
    public Map<LocalDate, Integer> byDay() {
        Map<LocalDate, Integer> tally = new TreeMap<>();
        landed.forEach(spot -> tally.merge(spot.day(), 1, Integer::sum));
        return tally;
    }

    /** The hour of every spot placed, sorted. */
    public List<Integer> hours() {
        return landed.stream().map(Landed::hour).sorted().toList();
    }

    void land(Landed spot) {
        landed.add(spot);
    }

    void miss(Miss spot) {
        missed.add(spot);
    }

    void sortLanded() {
        landed.sort(
                Comparator.comparing(Landed::day).thenComparingInt(Landed::hour).thenComparingInt(Landed::number));
    }
}
