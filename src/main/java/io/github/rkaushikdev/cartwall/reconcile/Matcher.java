package io.github.rkaushikdev.cartwall.reconcile;

import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.logs.DayLog;
import io.github.rkaushikdev.cartwall.logs.LogLine;
import io.github.rkaushikdev.cartwall.playout.AsRun;
import io.github.rkaushikdev.cartwall.playout.Played;
import io.github.rkaushikdev.cartwall.scheduling.LineKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Matching what the automation played against what the log said it would.
 *
 * <p>This is the step that turns a schedule into an invoice: nothing is billed off the log, only
 * off what actually aired. Matching runs in two passes and the order matters. First by reference,
 * when the automation echoes the order and line back: unambiguous, and it survives a moved break
 * and a cart that ran twice. Then by cart and time, nearest first, one to one, inside a window, for
 * the lines that lost their reference.
 */
public final class Matcher {

    /** How far the cart matcher will look for a partner when there is no reference. */
    public static final Duration WINDOW = Duration.ofSeconds(30 * 60);

    private Matcher() {}

    /** Compare one day's log against one day's as-run, inside the standard window. */
    public static Reconciliation reconcile(DayLog log, List<AsRun> rows) {
        return reconcile(log, rows, WINDOW);
    }

    /** Compare one day's log against one day's as-run. */
    public static Reconciliation reconcile(DayLog log, List<AsRun> rows, Duration window) {
        List<AsRun> wrongDay =
                rows.stream().filter(row -> !row.day().equals(log.day())).toList();
        if (!wrongDay.isEmpty()) {
            throw new ReconcileError(
                    "the as-run carries " + wrongDay.size() + " line(s) for another day",
                    Details.of(
                            "log_day", log.day().toString(),
                            "first", wrongDay.get(0).day().toString()));
        }
        List<LogLine> pending = new ArrayList<>(log.spots());
        List<AsRun> left = new ArrayList<>(rows);
        List<Match> matched = new ArrayList<>();
        byReference(pending, left, matched);
        byCart(pending, left, matched, window);

        List<Missed> missed = new ArrayList<>();
        for (LogLine entry : pending) {
            missed.add(new Missed(entry, why(entry, rows)));
        }
        List<Extra> extra = new ArrayList<>();
        for (AsRun row : left) {
            if (row.key().isPresent() || !row.cart().isEmpty()) {
                extra.add(new Extra(row));
            }
        }
        matched.sort(Comparator.comparing(match -> match.aired().at()));
        missed.sort(Comparator.comparing(miss -> miss.scheduled().at()));
        extra.sort(Comparator.comparing(each -> each.aired().at()));
        return new Reconciliation(log.station(), log.day(), matched, missed, extra);
    }

    /** How many spots were missed for each reason, worst first. */
    public static Map<String, Integer> missedByStatus(Reconciliation result) {
        Map<String, Integer> tally = new HashMap<>();
        result.missed().forEach(miss -> tally.merge(miss.reason(), 1, Integer::sum));
        Map<String, Integer> ordered = new LinkedHashMap<>();
        tally.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, Integer> entry) -> -entry.getValue())
                        .thenComparing(Map.Entry::getKey))
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }

    /** The furthest any matched spot ran from where it was logged. */
    public static Duration worstDrift(Reconciliation result) {
        return result.matched().stream()
                .map(Match::drift)
                .max(Comparator.naturalOrder())
                .orElse(Duration.ZERO);
    }

    public static Played statusOf(AsRun row) {
        return row.status();
    }

    /** The unambiguous pass. Same order, same line, first come first served. */
    private static void byReference(List<LogLine> pending, List<AsRun> left, List<Match> matched) {
        for (AsRun row : List.copyOf(left)) {
            Optional<LineKey> key = row.key();
            if (key.isEmpty() || !row.aired()) {
                continue;
            }
            for (LogLine entry : pending) {
                if (new LineKey(entry.order(), entry.line()).equals(key.get())) {
                    matched.add(new Match(entry, row, true));
                    pending.remove(entry);
                    left.remove(row);
                    break;
                }
            }
        }
    }

    /** The best guess pass. Same cart, nearest in time, inside the window. */
    private static void byCart(List<LogLine> pending, List<AsRun> left, List<Match> matched, Duration window) {
        for (AsRun row : List.copyOf(left)) {
            if (row.cart().isEmpty() || !row.aired()) {
                continue;
            }
            Optional<LogLine> best = pending.stream()
                    .filter(entry -> entry.cart().equals(row.cart()))
                    .filter(entry -> row.driftFrom(entry.at()).compareTo(window) <= 0)
                    .min(Comparator.comparingInt(
                            entry -> row.driftFrom(entry.at()).tenths()));
            if (best.isEmpty()) {
                continue;
            }
            matched.add(new Match(best.get(), row, false));
            pending.remove(best.get());
            left.remove(row);
        }
    }

    /** The as-run's own word for it, when there is a line saying it failed. */
    private static String why(LogLine entry, List<AsRun> rows) {
        LineKey key = new LineKey(entry.order(), entry.line());
        for (AsRun row : rows) {
            if (row.key().filter(key::equals).isPresent() && !row.aired()) {
                return row.status().value();
            }
            if (!row.cart().isEmpty() && row.cart().equals(entry.cart()) && !row.aired()) {
                return row.status().value();
            }
        }
        return Missed.NO_LINE;
    }
}
