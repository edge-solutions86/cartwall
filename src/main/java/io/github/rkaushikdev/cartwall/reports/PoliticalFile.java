package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.Money;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The political file, which is a legal obligation and not a report.
 *
 * <p>Every request for political time goes in it, accepted or turned down, and it goes in the day
 * it arrives. Nothing here decides anything: it keeps what is on file, in the order it was
 * requested, and refuses to render an entry that is missing something the rule requires.
 */
public final class PoliticalFile {

    private static final Comparator<FileEntry> REQUEST_ORDER = Comparator.comparing(FileEntry::requested)
            .thenComparing(entry -> entry.candidacy().candidate());

    private final List<FileEntry> entries = new ArrayList<>();

    public void file(FileEntry entry) {
        entries.add(entry);
        entries.sort(REQUEST_ORDER);
    }

    /** Everything on file, in the order it was requested. */
    public List<FileEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public List<FileEntry> forCandidate(String candidate) {
        return entries.stream()
                .filter(row -> row.candidacy().candidate().equals(candidate))
                .toList();
    }

    public List<FileEntry> forRace(String office) {
        return entries.stream()
                .filter(row -> row.candidacy().race().office().equals(office))
                .toList();
    }

    /** Entries that cannot be filed yet, which somebody has to chase. */
    public List<FileEntry> incomplete() {
        return entries.stream().filter(row -> !row.isComplete()).toList();
    }

    public List<FileEntry> rejected() {
        return entries.stream().filter(row -> !row.accepted()).toList();
    }

    /** What each candidate's accepted requests came to, biggest first. */
    public Map<String, Money> spendByCandidate() {
        Map<String, Money> tally = new HashMap<>();
        entries.stream()
                .filter(FileEntry::accepted)
                .forEach(row -> tally.merge(row.candidacy().candidate(), row.value(), Money::plus));
        Map<String, Money> ordered = new LinkedHashMap<>();
        tally.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, Money> pair) ->
                                -pair.getValue().cents())
                        .thenComparing(Map.Entry::getKey))
                .forEach(pair -> ordered.put(pair.getKey(), pair.getValue()));
        return ordered;
    }

    /** Every complete entry, rendered, one after another. */
    public String renderAll() {
        return entries.stream()
                .filter(FileEntry::isComplete)
                .map(FileEntry::render)
                .collect(Collectors.joining("\n"));
    }

    public int size() {
        return entries.size();
    }
}
