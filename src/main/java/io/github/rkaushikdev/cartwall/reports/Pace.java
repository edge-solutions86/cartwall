package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.Money;
import java.util.Optional;

/**
 * One period measured against another: last year, budget, or the same point last month.
 *
 * @param now the period being measured
 * @param then the period it is measured against
 */
public record Pace(Period now, Period then) {

    public int unitChange() {
        return now.sold() - then.sold();
    }

    public Money revenueChange() {
        return now.revenue().minus(then.revenue());
    }

    public double selloutChange() {
        return now.sellout() - then.sellout();
    }

    /** Whether the two periods had the same amount of inventory. */
    public boolean comparable() {
        return now.units() == then.units();
    }

    /** Revenue now as a fraction of revenue then, or nothing when then earned nothing. */
    public Optional<Double> index() {
        if (then.revenue().isZero()) {
            return Optional.empty();
        }
        return Optional.of(now.revenue().cents() / (double) then.revenue().cents());
    }

    @Override
    public String toString() {
        String note = comparable() ? "" : "  (different inventory)";
        return now.label() + " against " + then.label() + ": " + "%+d".formatted(unitChange()) + " units, "
                + revenueChange() + " " + Percent.signed(selloutChange()) + note;
    }
}
