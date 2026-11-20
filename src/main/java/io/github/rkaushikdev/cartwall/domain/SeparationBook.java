package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The separation rules one station runs, checked in the order they were filed.
 *
 * <p>Separation is the only part of traffic that is genuinely a constraint problem: whether a spot
 * may go where the schedule wants it depends on what is already there.
 */
public final class SeparationBook {

    private final List<SeparationRule> rules = new ArrayList<>();

    /** What every station in the cluster runs unless somebody files otherwise. */
    public static SeparationBook houseRules() {
        SeparationBook book = new SeparationBook();
        book.file(new SeparationRule("SEP-ADV", Subject.ADVERTISER, Scope.MINUTES, 30));
        book.file(new SeparationRule("SEP-CAT-BRK", Subject.CATEGORY, Scope.BREAK));
        book.file(new SeparationRule("SEP-CAT-MIN", Subject.CATEGORY, Scope.MINUTES, 15));
        book.file(new SeparationRule("SEP-CART", Subject.CART, Scope.HOUR));
        return book;
    }

    public List<SeparationRule> rules() {
        return Collections.unmodifiableList(rules);
    }

    public void file(SeparationRule rule) {
        if (rules.stream().anyMatch(filed -> filed.code().equals(rule.code()))) {
            throw new SeparationError("rule " + rule.code() + " is already filed");
        }
        rules.add(rule);
    }

    public SeparationRule rule(String code) {
        return rules.stream()
                .filter(filed -> filed.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new SeparationError("rule " + code + " is not filed"));
    }

    /** Every rule {@code candidate} breaks against what is already scheduled. */
    public List<Violation> violations(Candidate candidate, List<Candidate> neighbours) {
        List<Violation> found = new ArrayList<>();
        for (SeparationRule rule : rules) {
            for (Candidate other : neighbours) {
                if (other == candidate || !rule.tooClose(candidate, other)) {
                    continue;
                }
                Optional<Duration> gap =
                        rule.scope() == Scope.MINUTES ? Optional.of(candidate.gapTo(other)) : Optional.empty();
                found.add(new Violation(rule, other, gap));
            }
        }
        return List.copyOf(found);
    }

    public boolean allows(Candidate candidate, List<Candidate> neighbours) {
        return violations(candidate, neighbours).isEmpty();
    }

    public int size() {
        return rules.size();
    }
}
