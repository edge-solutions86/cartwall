package io.github.rkaushikdev.cartwall.billing;

import io.github.rkaushikdev.cartwall.Money;

/**
 * Gross, commission and net across a batch of invoices, for the run's own footer.
 *
 * @param gross the spots at the rates on the orders
 * @param commission what the agencies keep
 * @param net what is remitted
 */
public record Totals(Money gross, Money commission, Money net) {

    public static final Totals NONE = new Totals(Money.ZERO, Money.ZERO, Money.ZERO);
}
