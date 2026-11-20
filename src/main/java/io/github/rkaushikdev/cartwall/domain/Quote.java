package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.Money;
import java.math.BigDecimal;
import java.util.Map;

/**
 * One quoted thirty, for one daypart in one class. Every other length comes off it by a factor.
 *
 * <p>The factors are not proportional: a sixty is one and three quarters of a thirty, not two,
 * because the second thirty does not cost the station another unit of listener attention. A
 * fifteen is sixty five percent, which is more than half, for the same reason in reverse.
 *
 * @param daypart the daypart code
 * @param grade the class of time
 * @param thirty the price of a thirty
 */
public record Quote(String daypart, ClassOfTime grade, Money thirty) {

    /** Multiplied against the quoted thirty, by sold length in seconds. Not proportional. */
    public static final Map<Integer, BigDecimal> LENGTH_FACTORS = Map.of(
            10, new BigDecimal("0.50"),
            15, new BigDecimal("0.65"),
            20, new BigDecimal("0.75"),
            30, new BigDecimal("1.00"),
            45, new BigDecimal("1.40"),
            60, new BigDecimal("1.75"),
            90, new BigDecimal("2.50"),
            120, new BigDecimal("3.25"));

    public Quote {
        if (daypart == null || daypart.isEmpty()) {
            throw new RateError("a quote needs a daypart");
        }
        if (thirty.isNegative()) {
            throw new RateError(daypart + " is quoted at " + thirty);
        }
    }

    /** The price of {@code length} in this daypart and class. */
    public Money atLength(SpotLength length) {
        return thirty.scale(lengthFactor(length.seconds()));
    }

    /** The factor filed for a length, refusing rather than guessing one that is not filed. */
    static BigDecimal lengthFactor(int seconds) {
        BigDecimal factor = LENGTH_FACTORS.get(seconds);
        if (factor == null) {
            throw new RateError("no length factor is filed for " + Duration.ofSeconds(seconds));
        }
        return factor;
    }

    @Override
    public String toString() {
        return daypart + " " + grade.value() + " " + thirty;
    }
}
