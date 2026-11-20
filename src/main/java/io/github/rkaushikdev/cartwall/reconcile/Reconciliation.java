package io.github.rkaushikdev.cartwall.reconcile;

import io.github.rkaushikdev.cartwall.scheduling.LineKey;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one station day came to when the log and the as-run were compared.
 *
 * @param station the station
 * @param day the date
 * @param matched every scheduled spot the as-run answered for, in air order
 * @param missed every scheduled spot it did not, in log order
 * @param extra everything that aired and was not on the log, in air order
 */
public record Reconciliation(
        String station, LocalDate day, List<Match> matched, List<Missed> missed, List<Extra> extra) {

    public Reconciliation {
        matched = List.copyOf(matched);
        missed = List.copyOf(missed);
        extra = List.copyOf(extra);
    }

    public int scheduled() {
        return matched.size() + missed.size();
    }

    public int aired() {
        return (int) matched.stream().filter(Match::billable).count();
    }

    public List<Match> moved() {
        return matched.stream().filter(Match::moved).toList();
    }

    public boolean isClean() {
        return missed.isEmpty() && extra.isEmpty() && moved().isEmpty();
    }

    public List<Match> forLine(String order, int line) {
        LineKey key = new LineKey(order, line);
        return matched.stream().filter(match -> match.key().equals(key)).toList();
    }

    /** How many spots each line is short, worst first. */
    public Map<LineKey, Integer> owed() {
        Map<LineKey, Integer> tally = new HashMap<>();
        missed.forEach(miss -> tally.merge(miss.key(), 1, Integer::sum));
        Map<LineKey, Integer> ordered = new LinkedHashMap<>();
        tally.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<LineKey, Integer> entry) -> -entry.getValue())
                        .thenComparing(Map.Entry::getKey))
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }

    @Override
    public String toString() {
        return station + " " + day + ": " + aired() + " of " + scheduled() + " aired, " + missed.size() + " missed, "
                + extra.size() + " unlogged";
    }
}
