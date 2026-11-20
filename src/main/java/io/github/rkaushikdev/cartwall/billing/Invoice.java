package io.github.rkaushikdev.cartwall.billing;

import io.github.rkaushikdev.cartwall.BroadcastMonth;
import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One order, one broadcast month, what aired and what it comes to.
 *
 * <p>One invoice per order per broadcast month, because an agency reconciles against the order it
 * placed and the order was sold on the broadcast calendar. One detail line per spot in air order,
 * and a summary by line, because the seller reads that and the clerk does not read the detail at
 * all when the summary agrees with the order. Gross is the spots at the rate on the order,
 * commission comes off the gross, net is what the agency remits.
 *
 * @param number the invoice number
 * @param order the order it bills
 * @param advertiser whose spots they were
 * @param payer whose name the invoice carries
 * @param month the broadcast month
 * @param commissionRate what comes off the gross; zero for direct business
 * @param details every spot that aired, in air order
 * @param summaries the detail rolled up by order line
 * @param raised the day it was raised, once it has been
 */
public record Invoice(
        String number,
        String order,
        String advertiser,
        String payer,
        BroadcastMonth month,
        BigDecimal commissionRate,
        List<Detail> details,
        List<Summary> summaries,
        Optional<LocalDate> raised) {

    public Invoice {
        details = List.copyOf(details);
        summaries = List.copyOf(summaries);
        Objects.requireNonNull(raised, "raised");
        if (number == null || number.isEmpty()) {
            throw new InvoiceError("an invoice needs a number");
        }
        if (order == null || order.isEmpty()) {
            throw new InvoiceError("invoice " + number + " names no order");
        }
        if (commissionRate.signum() < 0 || commissionRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new InvoiceError(
                    "invoice " + number + " takes " + commissionRate + " off, which is not a commission");
        }
    }

    /** An invoice with nothing on it. Raised, then never sent. */
    public static Invoice empty(String number, String order, String advertiser, String payer, BroadcastMonth month) {
        return new Invoice(
                number, order, advertiser, payer, month, BigDecimal.ZERO, List.of(), List.of(), Optional.empty());
    }

    /**
     * Roll the detail up by order line, in line number order.
     *
     * @param lines where each line was bought, which the detail does not carry because it would be
     *     the same value on every row
     */
    public static List<Summary> summarise(List<Detail> details, Map<Integer, LineHeading> lines) {
        Map<Integer, List<Detail>> counted = new TreeMap<>();
        details.forEach(detail -> counted.computeIfAbsent(detail.line(), line -> new ArrayList<>())
                .add(detail));
        List<Summary> summaries = new ArrayList<>();
        for (Map.Entry<Integer, List<Detail>> entry : counted.entrySet()) {
            List<Detail> rows = entry.getValue();
            Set<Money> rates = new HashSet<>();
            rows.forEach(detail -> rates.add(detail.rate()));
            if (rates.size() > 1) {
                throw new InvoiceError(
                        "line " + entry.getKey() + " has " + rates.size() + " different rates on one invoice",
                        Details.of("line", entry.getKey()));
            }
            LineHeading heading = lines.getOrDefault(entry.getKey(), LineHeading.NONE);
            summaries.add(new Summary(
                    entry.getKey(),
                    heading.station(),
                    heading.daypart(),
                    rows.get(0).length(),
                    rows.get(0).rate(),
                    rows.size()));
        }
        return List.copyOf(summaries);
    }

    /** Gross, commission and net across a batch, for the run's own footer. */
    public static Totals runningTotal(List<Invoice> invoices) {
        if (invoices.isEmpty()) {
            return Totals.NONE;
        }
        return new Totals(
                Money.total(invoices.stream().map(Invoice::gross).toList()),
                Money.total(invoices.stream().map(Invoice::commission).toList()),
                Money.total(invoices.stream().map(Invoice::net).toList()));
    }

    public int spots() {
        return details.size();
    }

    public Money gross() {
        return Money.total(details.stream().map(Detail::rate).toList());
    }

    public Money commission() {
        return Money.commission(gross(), commissionRate);
    }

    public Money net() {
        return Money.toNet(gross(), commissionRate);
    }

    public boolean isEmpty() {
        return details.isEmpty();
    }

    public List<Detail> forLine(int line) {
        return details.stream().filter(detail -> detail.line() == line).toList();
    }

    public Optional<Summary> summaryFor(int line) {
        return summaries.stream().filter(summary -> summary.line() == line).findFirst();
    }

    /** The days the invoice covers, once each, in order. */
    public List<LocalDate> days() {
        TreeSet<LocalDate> days = new TreeSet<>();
        details.forEach(detail -> days.add(detail.day()));
        return List.copyOf(days);
    }

    /** The same invoice under the number it was given off the counter. */
    public Invoice withNumber(String number) {
        return new Invoice(number, order, advertiser, payer, month, commissionRate, details, summaries, raised);
    }

    /** The same invoice with the summary given. */
    public Invoice withSummaries(List<Summary> summaries) {
        return new Invoice(number, order, advertiser, payer, month, commissionRate, details, summaries, raised);
    }

    /** The summary and the detail have to agree, or nobody pays either. */
    public void checkAddsUp() {
        Money detail = gross();
        Money summed = Money.total(summaries.stream().map(Summary::gross).toList());
        if (!detail.equals(summed)) {
            throw new InvoiceError(
                    "invoice " + number + " details come to " + detail + " and the summary comes to " + summed,
                    Details.of("invoice", number, "detail", detail.toString(), "summary", summed.toString()));
        }
        int counted = summaries.stream().mapToInt(Summary::spots).sum();
        if (counted != spots()) {
            throw new InvoiceError(
                    "invoice " + number + " has " + spots() + " spots in detail and " + counted + " in the summary",
                    Details.of("invoice", number));
        }
    }

    @Override
    public String toString() {
        return number + " " + payer + " " + month + " " + spots() + " spots " + net();
    }
}
