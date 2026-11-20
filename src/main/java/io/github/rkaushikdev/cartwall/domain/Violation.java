package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * One rule, broken by one pair of spots.
 *
 * @param rule the rule broken
 * @param against the spot already scheduled that it was broken against
 * @param gap how far apart the two were, for a rule measured in minutes
 */
public record Violation(SeparationRule rule, Candidate against, Optional<Duration> gap) {

    public Violation {
        Objects.requireNonNull(gap, "gap");
    }

    @Override
    public String toString() {
        return gap.map(apart -> rule.code() + " against " + against + ", " + apart + " apart")
                .orElse(rule.code() + " against " + against);
    }
}
