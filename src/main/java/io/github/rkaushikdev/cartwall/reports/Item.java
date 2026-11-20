package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.Money;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * One invoice on a statement, with what is left on it.
 *
 * <p>Age is measured from the day the invoice was raised: raised is the only date that is a fact
 * on file. Credits age with the invoice they correct, so a credit written today against a ninety
 * day invoice makes it less money at ninety days, not current money.
 *
 * @param invoice the invoice number
 * @param order the order it billed
 * @param raised the day it was raised
 * @param billed what it billed
 * @param credited what has been credited against it
 */
public record Item(String invoice, String order, LocalDate raised, Money billed, Money credited) {

    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    public Money owing() {
        return billed.minus(credited);
    }

    /** Days since it was raised, as of {@code on}. */
    public long age(LocalDate on) {
        if (on.isBefore(raised)) {
            throw new StatementError(
                    "invoice " + invoice + " was raised on " + raised + ", after " + on,
                    Details.of("invoice", invoice));
        }
        return ChronoUnit.DAYS.between(raised, on);
    }

    public Bucket bucket(LocalDate on) {
        return Bucket.of(age(on));
    }

    @Override
    public String toString() {
        return "%-12s %s  %12s".formatted(invoice, SHORT_DATE.format(raised), owing());
    }
}
