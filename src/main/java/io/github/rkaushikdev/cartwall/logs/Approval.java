package io.github.rkaushikdev.cartwall.logs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What stands between a log and a signature.
 *
 * <p>Approval is gated on problems, not on warnings. A hole in a break is a warning and every log
 * has them. A spot with no cart is a problem, because the operator will reach the break and have
 * nothing to play, and an hour with no legal ID is a problem because that is the licence.
 */
public final class Approval {

    private Approval() {}

    /** Everything that has to be fixed before this log can be signed. */
    public static List<Problem> problems(DayLog log) {
        List<Problem> found = new ArrayList<>();
        for (LogLine spot : log.spots()) {
            if (spot.cart().isEmpty()) {
                found.add(new Problem(
                        "missing_copy",
                        spot.at().twelveHour(),
                        spot.order() + "-" + spot.line() + " for " + spot.advertiser() + " has no cart"));
            }
        }
        Set<Integer> withIds = new HashSet<>();
        log.legalIds().forEach(entry -> withIds.add(entry.hour()));
        for (int hour : log.hours()) {
            if (!withIds.contains(hour)) {
                found.add(new Problem("no_legal_id", "hour " + hour, "the hour carries no legal identification"));
            }
        }
        return List.copyOf(found);
    }

    /** Worth saying, not worth stopping for. */
    public static List<Problem> warnings(DayLog log) {
        int holes = log.openUnits();
        if (holes == 0) {
            return List.of();
        }
        return List.of(new Problem("unsold", log.station() + " " + log.day(), holes + " units did not sell"));
    }

    /** Order and line of every spot the operator would have nothing to play for. */
    public static List<String> missingCopy(DayLog log) {
        TreeSet<String> missing = new TreeSet<>();
        for (LogLine entry : log.lines()) {
            if (entry.element() == Element.SPOT && entry.cart().isEmpty()) {
                missing.add(entry.order() + "-" + entry.line());
            }
        }
        return List.copyOf(missing);
    }
}
