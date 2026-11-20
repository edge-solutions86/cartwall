package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.Money;
import java.time.LocalDate;

/**
 * What one span came to, with its denominator travelling beside the number.
 *
 * <p>A week with a ballgame in it has fewer units than the week before, so raw sold counts fall
 * while sellout rises. Every figure here carries its own denominator so a good week does not get
 * reported as a bad one. Money is at the rate the spots were sold at, not at card.
 *
 * @param label what the report calls it
 * @param first the first day
 * @param last the last day
 * @param units units available
 * @param sold units sold
 * @param revenue what they sold for
 */
public record Period(String label, LocalDate first, LocalDate last, int units, int sold, Money revenue) {

    public Period {
        if (last.isBefore(first)) {
            throw new SelloutError(label + " runs backwards");
        }
        if (sold > units) {
            throw new SelloutError(
                    label + " sold " + sold + " of " + units + ", which is oversold",
                    Details.of("label", label, "sold", sold, "units", units));
        }
    }

    /** A period nobody has put a price on. */
    public Period(String label, LocalDate first, LocalDate last, int units, int sold) {
        this(label, first, last, units, sold, Money.ZERO);
    }

    /** Units still unsold. */
    public int open() {
        return units - sold;
    }

    public double sellout() {
        if (units == 0) {
            return 0.0;
        }
        return sold / (double) units;
    }

    /** What the average sold unit went for, rounded down to the cent. */
    public Money rate() {
        if (sold == 0) {
            return Money.ZERO;
        }
        return new Money(Math.floorDiv(revenue.cents(), sold));
    }

    @Override
    public String toString() {
        return "%-14s %6d of %-6d %6s  %s".formatted(label, sold, units, Percent.of(sellout()), revenue);
    }
}
