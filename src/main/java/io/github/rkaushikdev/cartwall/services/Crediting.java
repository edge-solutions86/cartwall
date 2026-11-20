package io.github.rkaushikdev.cartwall.services;

import static io.github.rkaushikdev.cartwall.HouseLog.Field.field;

import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.HouseLog;
import io.github.rkaushikdev.cartwall.LookupFailure;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.persistence.models.CreditRow;
import io.github.rkaushikdev.cartwall.persistence.models.InvoiceRow;
import io.github.rkaushikdev.cartwall.persistence.models.Reason;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writing a credit, and working out what an account actually owes after them.
 *
 * <p>The rules a credit holds to: it points at an invoice, always; it cannot exceed what is left
 * on the invoice (crediting more than was billed turns an invoice into a payable, which is a
 * refund and a different conversation); commission comes off it at the rate the invoice carried,
 * so an agency is never quietly charged fifteen percent for a spot that did not run; and a credit
 * for some other reason needs a note, because the month end report groups by reason.
 */
public final class Crediting {

    /** The series credit numbers are drawn from. Separate from invoices on purpose. */
    public static final String SERIES = "CRD";

    private Crediting() {}

    /** The credits written against one invoice, in number order. */
    public static List<CreditRow> creditsAgainst(UnitOfWork uow, String invoice) {
        return uow.entityManager()
                .createQuery(
                        "select c from CreditRow c where c.invoiceNumber = :invoice order by c.number", CreditRow.class)
                .setParameter("invoice", invoice)
                .getResultList();
    }

    /** What is left owing on one invoice. */
    public static Standing standing(UnitOfWork uow, String invoiceNumber) {
        InvoiceRow invoice = invoice(uow, invoiceNumber);
        Money applied = Money.total(creditsAgainst(uow, invoiceNumber).stream()
                .map(CreditRow::getNet)
                .toList());
        return new Standing(invoiceNumber, invoice.getNet(), applied);
    }

    /** Write one credit against one invoice, with no note and no spots counted. */
    public static CreditRow raiseCredit(
            UnitOfWork uow, String invoiceNumber, Money gross, Reason reason, LocalDate raisedOn) {
        return raiseCredit(uow, invoiceNumber, gross, reason, raisedOn, "", 0);
    }

    /** Write one credit against one invoice. */
    public static CreditRow raiseCredit(
            UnitOfWork uow,
            String invoiceNumber,
            Money gross,
            Reason reason,
            LocalDate raisedOn,
            String note,
            int spots) {
        if (gross.cents() <= 0) {
            throw new CreditError("a credit of " + gross + " is not a credit", Details.of("amount", gross.toString()));
        }
        if (reason.needsANote() && note.strip().isEmpty()) {
            throw new CreditError("a credit for some other reason needs a note");
        }
        InvoiceRow invoice = invoice(uow, invoiceNumber);

        Money taken = Money.commission(gross, invoice.getCommissionRate());
        Money net = Money.toNet(gross, invoice.getCommissionRate());
        Money left = standing(uow, invoiceNumber).owing();
        if (net.compareTo(left) > 0) {
            throw new OverCredited(
                    invoiceNumber + " has " + left + " left and this credit is " + net,
                    Details.of("invoice", invoiceNumber, "left", left.toString(), "asked", net.toString()));
        }

        CreditRow row = new CreditRow(
                new Numbering(uow.entityManager()).next(SERIES, raisedOn.getYear()),
                invoiceNumber,
                invoice.getAdvertiser(),
                invoice.getPayer(),
                raisedOn,
                reason,
                gross,
                taken,
                net);
        row.setNote(note.strip());
        row.setSpots(spots);
        uow.entityManager().persist(row);
        uow.entityManager().flush();
        HouseLog.event(
                "credit.raised",
                field("credit", row.getNumber()),
                field("invoice", invoiceNumber),
                field("payer", invoice.getPayer()),
                field("reason", reason.value()),
                field("gross", gross.toString()),
                field("net", net.toString()));
        return row;
    }

    /** The political case, which has its own reason and needs no note. */
    public static CreditRow creditTheRebate(UnitOfWork uow, String invoiceNumber, Money amount, LocalDate raisedOn) {
        return raiseCredit(uow, invoiceNumber, amount, Reason.POLITICAL_REBATE, raisedOn);
    }

    /** What was credited in a span, grouped by reason, the most credited first. */
    public static Map<String, Money> byReason(UnitOfWork uow, LocalDate first, LocalDate last) {
        if (last.isBefore(first)) {
            throw new CreditError("the span runs backwards");
        }
        List<CreditRow> rows = uow.entityManager()
                .createQuery(
                        "select c from CreditRow c where c.raised >= :first and c.raised <= :last", CreditRow.class)
                .setParameter("first", first)
                .setParameter("last", last)
                .getResultList();
        Map<String, Money> tally = new TreeMap<>();
        for (CreditRow row : rows) {
            tally.merge(row.getReason().value(), row.getNet(), Money::plus);
        }
        Map<String, Money> sorted = new LinkedHashMap<>();
        tally.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, Money> pair) ->
                                -pair.getValue().cents())
                        .thenComparing(Map.Entry::getKey))
                .forEach(pair -> sorted.put(pair.getKey(), pair.getValue()));
        return sorted;
    }

    /** Every invoice with something still owing, most owing first. */
    public static List<Standing> outstanding(UnitOfWork uow) {
        List<InvoiceRow> invoices = uow.entityManager()
                .createQuery("select i from InvoiceRow i order by i.number", InvoiceRow.class)
                .getResultList();
        return invoices.stream()
                .map(invoice -> standing(uow, invoice.getNumber()))
                .filter(entry -> !entry.isSettled())
                .sorted(Comparator.comparing((Standing entry) -> -entry.owing().cents())
                        .thenComparing(Standing::invoice))
                .toList();
    }

    private static InvoiceRow invoice(UnitOfWork uow, String number) {
        InvoiceRow invoice = uow.entityManager().find(InvoiceRow.class, number);
        if (invoice == null) {
            throw new LookupFailure("invoice " + number + " is not on file", Details.of("invoice", number));
        }
        return invoice;
    }

    /** What an invoice comes to once its credits are applied. */
    public record Standing(String invoice, Money billed, Money credited) {

        public Money owing() {
            return billed.minus(credited);
        }

        public boolean isSettled() {
            return owing().equals(Money.ZERO);
        }

        @Override
        public String toString() {
            return invoice + " billed " + billed + " less " + credited + " leaves " + owing();
        }
    }
}
