package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.domain.Violation;
import java.util.List;

/**
 * One avail an ask could use, and what it would cost in separation violations.
 *
 * @param avail the break
 * @param violations every rule the spot would break there; empty when it is clean
 */
public record Opening(Avail avail, List<Violation> violations) {

    public Opening {
        violations = List.copyOf(violations);
    }

    public boolean isClean() {
        return violations.isEmpty();
    }

    public int hour() {
        return avail.hour();
    }

    public int number() {
        return avail.number();
    }

    /** The code of each rule broken, in the order they were checked. */
    public List<String> reasons() {
        return violations.stream().map(violation -> violation.rule().code()).toList();
    }

    @Override
    public String toString() {
        if (isClean()) {
            return avail + " clean";
        }
        return avail + " blocked by " + String.join(", ", reasons());
    }
}
