package io.github.rkaushikdev.cartwall.services;

import static io.github.rkaushikdev.cartwall.HouseLog.Field.field;

import io.github.rkaushikdev.cartwall.HouseLog;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.scheduling.LineKey;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The nightly run that turns what is owed into debts somebody can answer: gather, then write, then
 * say what changed.
 *
 * <p>Three things make it safe to run more than once a night. Raising an existing debt is a no-op,
 * so the count that comes back is new debts, not debts seen. Nothing is ever un-raised: a debt the
 * gather no longer finds stays on file, because a run that quietly cancels what a client is owed is
 * the one failure nobody notices until the client rings. And offers whose air date has gone by are
 * expired, because an open offer for last Tuesday makes the debt look answered on every report.
 */
public final class Raising {

    private Raising() {}

    /** Gather every debt in the span and put the new ones on file, expiring against the span's end. */
    public static Raised raiseOwing(UnitOfWork uow, LocalDate first, LocalDate last) {
        return raiseOwing(uow, first, last, true, Optional.empty());
    }

    /**
     * Gather every debt in the span and put the new ones on file.
     *
     * @param scheduled run the never scheduled pass as well
     * @param today the day offers are expired against. It defaults to the end of the span rather
     *     than the wall clock: running last month's span in November should expire last month's
     *     offers, not everything.
     */
    public static Raised raiseOwing(
            UnitOfWork uow, LocalDate first, LocalDate last, boolean scheduled, Optional<LocalDate> today) {
        List<Debt> debts = Owing.gather(uow, first, last, scheduled).debts();
        int added = uow.makegoods().raiseAll(debts);
        int expired = uow.makegoods().expireBefore(today.orElse(last)).size();

        Map<String, Integer> tally = new TreeMap<>();
        for (Debt debt : debts) {
            tally.merge(debt.cause().value(), 1, Integer::sum);
        }
        Raised report = new Raised(first, last, debts.size(), added, expired, worstFirst(tally));

        HouseLog.event(
                "makegood.raised",
                field("first", first),
                field("last", last),
                field("found", report.found()),
                field("new", report.added()),
                field("expired", report.expired()));
        if (report.added() > 0) {
            HouseLog.warn("makegood.owed", field("first", first), field("last", last), field("spots", report.added()));
        }
        return report;
    }

    /** What is still owed on every order, at the rate each spot was sold at. */
    public static Money owedValue(UnitOfWork uow) {
        return owedValue(uow, Optional.empty());
    }

    /** What is still owed, at the rate each spot was sold at. */
    public static Money owedValue(UnitOfWork uow, Optional<String> order) {
        return uow.makegoods().ledger(order, Optional.empty()).owedValue();
    }

    /** The ten lines with the most spots outstanding, worst first. */
    public static List<WorstLine> worstLines(UnitOfWork uow) {
        return worstLines(uow, 10);
    }

    /**
     * The lines with the most spots outstanding, worst first. This is the Monday morning call: the
     * useful answer is which three clients to ring, not what the total is.
     */
    public static List<WorstLine> worstLines(UnitOfWork uow, int limit) {
        Map<LineKey, Integer> owed = uow.makegoods().ledger().owedByLine();
        return owed.entrySet().stream()
                .limit(limit)
                .map(entry ->
                        new WorstLine(entry.getKey().order(), entry.getKey().line(), entry.getValue()))
                .toList();
    }

    /**
     * Why the open debt is open, worst first, every cause named even at zero. A month where never
     * scheduled dominates is a selling problem; one where did not run dominates is an automation
     * problem. Different people fix them.
     */
    public static Map<String, Integer> causes(UnitOfWork uow) {
        Map<String, Integer> tally = new TreeMap<>(uow.makegoods().ledger().byCause());
        for (Cause cause : Cause.values()) {
            tally.putIfAbsent(cause.value(), 0);
        }
        return worstFirst(tally);
    }

    private static Map<String, Integer> worstFirst(Map<String, Integer> tally) {
        Map<String, Integer> sorted = new LinkedHashMap<>();
        tally.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, Integer> pair) -> -pair.getValue())
                        .thenComparing(Map.Entry::getKey))
                .forEach(pair -> sorted.put(pair.getKey(), pair.getValue()));
        return sorted;
    }

    /**
     * What one run of the nightly job changed.
     *
     * @param found debts the gather found, whether or not they were already on file
     * @param added of those, the ones that were not on file before
     * @param expired offers withdrawn because their air date had gone by
     */
    public record Raised(
            LocalDate first, LocalDate last, int found, int added, int expired, Map<String, Integer> byCause) {

        public Raised {
            byCause = Collections.unmodifiableMap(new LinkedHashMap<>(byCause));
        }

        public boolean changed() {
            return added > 0 || expired > 0;
        }

        @Override
        public String toString() {
            return first + " to " + last + ": " + added + " new of " + found + " owed, " + expired
                    + " offer(s) expired";
        }
    }

    /** One line with spots still owed against it. */
    public record WorstLine(String order, int line, int count) {}
}
