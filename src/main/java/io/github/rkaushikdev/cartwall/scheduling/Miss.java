package io.github.rkaushikdev.cartwall.scheduling;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One spot that could not be placed, and the nearest thing to a reason.
 *
 * @param day the day it was meant for, or empty when the week had no air days at all
 * @param reason what stopped it
 * @param blocked how many openings each separation rule cost, worst first
 */
public record Miss(Optional<LocalDate> day, String reason, Map<String, Integer> blocked) {

    public Miss {
        Objects.requireNonNull(day, "day");
        blocked = Collections.unmodifiableMap(new LinkedHashMap<>(blocked));
    }

    /** A miss on a day, with no separation rule to blame. */
    public Miss(LocalDate day, String reason) {
        this(Optional.of(day), reason, Map.of());
    }

    @Override
    public String toString() {
        return day.map(LocalDate::toString).orElse("the week") + ": " + reason;
    }
}
