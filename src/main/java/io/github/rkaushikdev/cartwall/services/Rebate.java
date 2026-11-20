package io.github.rkaushikdev.cartwall.services;

import static io.github.rkaushikdev.cartwall.HouseLog.Field.field;

import io.github.rkaushikdev.cartwall.CartwallError;
import io.github.rkaushikdev.cartwall.HouseLog;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.Charge;
import io.github.rkaushikdev.cartwall.domain.ChargeBook;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.domain.Race;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.persistence.models.InvoiceDetailRow;
import io.github.rkaushikdev.cartwall.persistence.models.InvoiceRow;
import io.github.rkaushikdev.cartwall.persistence.models.InvoiceSummaryRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Recomputing what political accounts are owed back under the lowest unit charge rule, run
 * whenever a rate moves inside a political window and once at the end of it.
 *
 * <p>The measurement is taken off invoice detail, not off orders or the rate card: what was
 * charged is what was invoiced. The comparison is per class and per length, station by station.
 * Nothing here writes a credit; it works out what is owed and hands back a list, because a rebate
 * that appears without a memo is a rebate nobody can explain to an auditor.
 */
public final class Rebate {

    private Rebate() {}

    /**
     * Every rate that appeared on an invoice for air in the window, one charge per line of detail.
     * The rule is about the rate charged for a unit of time, and the detail carries it.
     */
    public static ChargeBook chargeBook(UnitOfWork uow, LocalDate first, LocalDate last) {
        List<InvoiceDetailRow> rows = uow.entityManager()
                .createQuery(
                        "select d from InvoiceDetailRow d join d.invoice i where d.day >= :first and d.day <= :last"
                                + " order by d.day, d.id",
                        InvoiceDetailRow.class)
                .setParameter("first", first)
                .setParameter("last", last)
                .getResultList();
        Map<String, Order> orders = new HashMap<>();
        List<Charge> charges = new ArrayList<>();
        for (InvoiceDetailRow row : rows) {
            InvoiceRow invoice = row.getInvoice();
            Order order = orders.computeIfAbsent(
                    invoice.getOrderNumber(), number -> uow.orders().get(number));
            Optional<Line> line = lineOf(order, row.getLine());
            line.ifPresent(sold -> charges.add(new Charge(
                    sold.station(),
                    sold.daypart(),
                    sold.grade(),
                    row.getLength(),
                    row.getRate(),
                    row.getDay(),
                    invoice.getAdvertiser())));
        }
        return new ChargeBook(charges);
    }

    /** What each named political account is owed for air inside the window, against what was invoiced. */
    public static RebateReport owedFor(UnitOfWork uow, Race race, List<String> advertisers) {
        return owedFor(uow, race, advertisers, Optional.empty());
    }

    /**
     * What each named political account is owed for air inside the window.
     *
     * @param book the charges to measure against; hand one in to run a what-if
     */
    public static RebateReport owedFor(UnitOfWork uow, Race race, List<String> advertisers, Optional<ChargeBook> book) {
        if (advertisers.isEmpty()) {
            throw new RebateError("a rebate run needs at least one political account");
        }
        LocalDate first = race.windowOpens();
        LocalDate last = race.electionDay();
        ChargeBook charges = book.orElseGet(() -> chargeBook(uow, first, last));
        RebateReport report = new RebateReport(race);

        List<InvoiceRow> invoices = uow.entityManager()
                .createQuery(
                        "select i from InvoiceRow i where i.advertiser in :advertisers order by i.number",
                        InvoiceRow.class)
                .setParameter("advertisers", advertisers)
                .getResultList();
        for (InvoiceRow invoice : invoices) {
            Order order = uow.orders().get(invoice.getOrderNumber());
            for (InvoiceSummaryRow summary : invoice.getSummaries()) {
                Optional<Line> found = lineOf(order, summary.getLine());
                if (found.isEmpty()) {
                    HouseLog.warn(
                            "rebate.orphan",
                            field("invoice", invoice.getNumber()),
                            field("line", summary.getLine()),
                            field("order", invoice.getOrderNumber()));
                    continue;
                }
                Line line = found.get();
                int spots = spotsInWindow(invoice, summary.getLine(), first, last);
                if (spots == 0) {
                    continue;
                }
                report.checked += 1;
                Optional<Money> lowest =
                        charges.lowest(line.station(), line.daypart(), line.grade(), summary.getLength(), first, last);
                if (lowest.isEmpty() || summary.getRate().compareTo(lowest.get()) <= 0) {
                    continue;
                }
                report.owed.add(new Owed(
                        invoice.getNumber(),
                        invoice.getOrderNumber(),
                        summary.getLine(),
                        invoice.getAdvertiser(),
                        line.station(),
                        line.daypart(),
                        line.grade(),
                        summary.getRate(),
                        lowest.get(),
                        spots));
            }
        }
        HouseLog.event(
                "rebate.checked",
                field("race", race.toString()),
                field("lines", report.checked),
                field("owed", report.owed.size()),
                field("amount", report.total().toString()));
        return report;
    }

    private static Optional<Line> lineOf(Order order, int number) {
        try {
            return Optional.of(order.line(number));
        } catch (CartwallError notOnTheOrder) {
            return Optional.empty();
        }
    }

    private static int spotsInWindow(InvoiceRow invoice, int line, LocalDate first, LocalDate last) {
        return (int) invoice.getDetails().stream()
                .filter(detail -> detail.getLine() == line
                        && !detail.getDay().isBefore(first)
                        && !detail.getDay().isAfter(last))
                .count();
    }

    /** One invoice, one line, and the difference somebody is owed. */
    public record Owed(
            String invoice,
            String order,
            int line,
            String advertiser,
            String station,
            String daypart,
            ClassOfTime grade,
            Money charged,
            Money lowest,
            int spots) {

        /** Owed back on each spot. */
        public Money each() {
            return ChargeBook.owedBack(charged, Optional.of(lowest));
        }

        public Money amount() {
            return each().times(spots);
        }

        @Override
        public String toString() {
            return invoice + " line " + line + " " + station + " " + daypart + ": " + spots + " at " + charged
                    + " against " + lowest + ", owed " + amount();
        }
    }

    /** What one recomputation found. */
    public static final class RebateReport {

        private final Race race;
        private final List<Owed> owed = new ArrayList<>();
        private int checked;

        public RebateReport(Race race) {
            this.race = race;
        }

        public Race race() {
            return race;
        }

        public List<Owed> owed() {
            return List.copyOf(owed);
        }

        /** How many invoice lines inside the window were measured. */
        public int checked() {
            return checked;
        }

        public Money total() {
            return Money.total(owed.stream().map(Owed::amount).toList());
        }

        public boolean isClean() {
            return owed.isEmpty();
        }

        public List<Owed> forAdvertiser(String code) {
            return owed.stream()
                    .filter(entry -> entry.advertiser().equals(code))
                    .toList();
        }

        /** What each account is owed, worst first. */
        public Map<String, Money> byAdvertiser() {
            Map<String, Money> tally = new TreeMap<>();
            for (Owed entry : owed) {
                tally.merge(entry.advertiser(), entry.amount(), Money::plus);
            }
            Map<String, Money> sorted = new LinkedHashMap<>();
            tally.entrySet().stream()
                    .sorted(Comparator.comparing((Map.Entry<String, Money> pair) ->
                                    -pair.getValue().cents())
                            .thenComparing(Map.Entry::getKey))
                    .forEach(pair -> sorted.put(pair.getKey(), pair.getValue()));
            return sorted;
        }

        @Override
        public String toString() {
            return race + ": " + checked + " line(s) checked, " + owed.size() + " owed, " + total() + " in total";
        }
    }
}
