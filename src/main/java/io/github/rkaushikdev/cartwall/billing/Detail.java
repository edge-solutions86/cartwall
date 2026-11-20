package io.github.rkaushikdev.cartwall.billing;

import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * One spot on the invoice, as it aired.
 *
 * <p>An agency that cannot see the times will not pay, and the time they want is the one off the
 * as-run, not the log.
 *
 * @param day the date it aired
 * @param at the time it really ran, as printed
 * @param line the order line
 * @param length the sold length
 * @param rate the rate on the order
 * @param cart the cart that played
 * @param moved set when the spot ran somewhere other than where it was sold
 */
public record Detail(LocalDate day, String at, int line, SpotLength length, Money rate, String cart, boolean moved) {

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM/dd", Locale.ROOT);

    @Override
    public String toString() {
        return MONTH_DAY.format(day) + " " + at + " " + length + " " + rate;
    }
}
