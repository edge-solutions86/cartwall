package io.github.rkaushikdev.cartwall.scheduling;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything that came off the air, waiting for the makegood run.
 *
 * <p>A bump is never free and never silent. A spot that is bumped and forgotten is a spot the
 * client paid for and did not get, which turns into a credit at the end of the month and a phone
 * call before that.
 */
public final class BumpQueue implements Iterable<Bumped> {

    private final List<Bumped> entries = new ArrayList<>();

    public void add(Bumped bumped) {
        entries.add(bumped);
    }

    public List<Bumped> entries() {
        return Collections.unmodifiableList(entries);
    }

    public List<Bumped> forOrder(String order) {
        return entries.stream()
                .filter(entry -> entry.placement().order().equals(order))
                .toList();
    }

    public List<Bumped> forLine(String order, int line) {
        LineKey key = new LineKey(order, line);
        return entries.stream().filter(entry -> entry.key().equals(key)).toList();
    }

    /** How many spots each line is owed, worst first. */
    public Map<LineKey, Integer> owed() {
        Map<LineKey, Integer> tally = new HashMap<>();
        entries.forEach(entry -> tally.merge(entry.key(), 1, Integer::sum));
        Map<LineKey, Integer> ordered = new LinkedHashMap<>();
        tally.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<LineKey, Integer> entry) -> -entry.getValue())
                        .thenComparing(Map.Entry::getKey))
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }

    public List<Bumped> on(LocalDate day) {
        return entries.stream().filter(entry -> entry.day().equals(day)).toList();
    }

    public int size() {
        return entries.size();
    }

    @Override
    public Iterator<Bumped> iterator() {
        return entries().iterator();
    }
}
