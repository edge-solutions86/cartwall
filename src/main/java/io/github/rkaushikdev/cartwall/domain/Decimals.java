package io.github.rkaushikdev.cartwall.domain;

import java.math.BigDecimal;

/**
 * Decimals that compare by value.
 *
 * <p>A database column with a scale hands back {@code 0.1500} for a commission filed as {@code
 * 0.15}, and {@code 1540.0} for a frequency filed as {@code 1540}. They are the same number, and a
 * record compared field by field has to say so.
 */
final class Decimals {

    private Decimals() {}

    /** The same number with no trailing zeros, and never in exponent form. */
    static BigDecimal normalized(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
