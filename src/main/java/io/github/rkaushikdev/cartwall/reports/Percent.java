package io.github.rkaushikdev.cartwall.reports;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A fraction written as a percentage to one place, the way the reports have always printed it.
 *
 * <p>The figure rounds the exact value of the double half to even, so a sellout of one sixteenth
 * prints as 6.2% and not 6.3%. Java's own {@code %.1f} rounds half up, which is why this exists
 * rather than a format string.
 */
final class Percent {

    private Percent() {}

    /** {@code 40.0%}. */
    static String of(double fraction) {
        return scaled(fraction).toPlainString() + "%";
    }

    /** {@code +10.0%} or {@code -10.0%}: always signed. */
    static String signed(double fraction) {
        String sign = fraction < 0 || isNegativeZero(fraction) ? "-" : "+";
        return sign + scaled(fraction).abs().toPlainString() + "%";
    }

    private static BigDecimal scaled(double fraction) {
        return new BigDecimal(fraction * 100).setScale(1, RoundingMode.HALF_EVEN);
    }

    private static boolean isNegativeZero(double fraction) {
        return Double.doubleToRawLongBits(fraction) == Double.doubleToRawLongBits(-0.0);
    }
}
