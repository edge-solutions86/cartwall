package io.github.rkaushikdev.cartwall.reconcile;

import io.github.rkaushikdev.cartwall.playout.AsRun;

/**
 * On the as-run, not on the log. Nobody has been billed and nobody is owed, but somewhere there
 * is inventory that was given away.
 *
 * @param aired the as-run line
 */
public record Extra(AsRun aired) {

    @Override
    public String toString() {
        return aired.cart() + " at " + aired.at().twelveHour() + " was not on the log";
    }
}
