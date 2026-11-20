package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.Duration;

/**
 * One house rule, filed once and checked against every candidate.
 *
 * @param code the rule's code
 * @param subject what it keeps apart
 * @param scope how far apart
 * @param minutes the gap, for a rule scoped to minutes; zero otherwise
 */
public record SeparationRule(String code, Subject subject, Scope scope, int minutes) {

    public SeparationRule {
        if (code == null || code.isEmpty()) {
            throw new SeparationError("a separation rule needs a code");
        }
        if (scope == Scope.MINUTES && minutes < 1) {
            throw new SeparationError(code + " is scoped to minutes but names none");
        }
        if (scope != Scope.MINUTES && minutes != 0) {
            throw new SeparationError(code + " is scoped to the " + scope.value() + ", not minutes");
        }
    }

    /** A rule scoped to the break or the hour. */
    public SeparationRule(String code, Subject subject, Scope scope) {
        this(code, subject, scope, 0);
    }

    public Duration gap() {
        return Duration.ofSeconds(minutes * 60);
    }

    /** Whether this rule has anything to say about these two spots. */
    public boolean subjectsTouch(Candidate one, Candidate other) {
        return switch (subject) {
            case ADVERTISER -> one.advertiser().code().equals(other.advertiser().code());
            case CATEGORY -> one.advertiser().conflictsWith(other.advertiser());
            case CART -> !one.cart().isEmpty() && one.cart().equals(other.cart());
            case AGENCY -> one.agency().isPresent() && one.agency().equals(other.agency());
        };
    }

    /** Whether these two spots break this rule. */
    public boolean tooClose(Candidate one, Candidate other) {
        if (!one.day().equals(other.day()) || !subjectsTouch(one, other)) {
            return false;
        }
        return switch (scope) {
            case BREAK -> one.breakNumber() == other.breakNumber() && one.hour() == other.hour();
            case HOUR -> one.hour() == other.hour();
            case MINUTES -> one.gapTo(other).compareTo(gap()) < 0;
        };
    }

    @Override
    public String toString() {
        if (scope == Scope.MINUTES) {
            return code + ": " + subject.value() + " " + minutes + " minutes apart";
        }
        return code + ": " + subject.value() + " not twice in a " + scope.value();
    }
}
