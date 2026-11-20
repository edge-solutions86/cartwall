package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.Money;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * How full the station is, and how that compares with where it should be.
 *
 * <p>Sellout is a fact about a span: units sold over units available. Pacing is sellout compared
 * with something, and every comparison here keeps the denominators visible.
 */
public final class Sellout {

    private static final Comparator<Period> FULLNESS = Comparator.comparingDouble(Period::sellout)
            .thenComparingLong(period -> period.revenue().cents());

    private Sellout() {}

    public static Pace pace(Period now, Period then) {
        return new Pace(now, then);
    }

    /** How far off budget the period came, and by what fraction. */
    public static BudgetGap againstBudget(Period now, Money budget) {
        Money gap = now.revenue().minus(budget);
        if (budget.isZero()) {
            return new BudgetGap(gap, Optional.empty());
        }
        return new BudgetGap(gap, Optional.of(now.revenue().cents() / (double) budget.cents()));
    }

    /** The fullest period, by sellout rather than by units. Periods with no inventory are left out. */
    public static Optional<Period> busiest(List<Period> periods) {
        return periods.stream().filter(period -> period.units() > 0).max(FULLNESS);
    }

    /** The emptiest period, by sellout. Periods with no inventory are left out. */
    public static Optional<Period> thinnest(List<Period> periods) {
        return periods.stream().filter(period -> period.units() > 0).min(FULLNESS);
    }

    /** Several periods as one, denominators added too. Refuses to roll up an empty list. */
    public static Period rolledUp(List<Period> periods, String label) {
        if (periods.isEmpty()) {
            throw new SelloutError("nothing to roll up");
        }
        return new Period(
                label,
                periods.stream()
                        .map(Period::first)
                        .min(Comparator.naturalOrder())
                        .orElseThrow(),
                periods.stream()
                        .map(Period::last)
                        .max(Comparator.<LocalDate>naturalOrder())
                        .orElseThrow(),
                periods.stream().mapToInt(Period::units).sum(),
                periods.stream().mapToInt(Period::sold).sum(),
                Money.total(periods.stream().map(Period::revenue).toList()));
    }
}
