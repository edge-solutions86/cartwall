package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.Money;

/**
 * One line of an avails report: what a daypart, an hour or a day has, has sold, and has left.
 *
 * @param label the daypart code, the hour or the day
 * @param units units available
 * @param sold units sold
 * @param value what the hole would fetch at card; a ceiling, not a forecast
 */
public record AvailsRow(String label, int units, int sold, Money value) {

    /** A row nobody has priced. */
    public AvailsRow(String label, int units, int sold) {
        this(label, units, sold, Money.ZERO);
    }

    /** Units still to sell. */
    public int open() {
        return units - sold;
    }

    public double sellout() {
        if (units == 0) {
            return 0.0;
        }
        return sold / (double) units;
    }

    @Override
    public String toString() {
        String counted = "%5d of %-5d".formatted(sold, units);
        return "%-8s %s %6s %s".formatted(label, counted, Percent.of(sellout()), value);
    }
}
