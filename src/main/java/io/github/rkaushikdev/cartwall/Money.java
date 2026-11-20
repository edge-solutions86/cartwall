package io.github.rkaushikdev.cartwall;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Money in whole cents, and the fifteen percent that never quite reverses.
 *
 * <p>Every amount in the system is an integer number of cents. No floating point reaches a
 * statement: a tenth of a cent repeated four thousand times is a dollar the agency will short pay
 * over. Negative is allowed, because credits exist.
 *
 * <p>The commission is the part worth reading twice. An agency buys at net and the station books
 * at gross, and the two are related by fifteen percent of the gross, not of the net: {@code net =
 * gross * 0.85}. Going the other way divides, and division does not land on a cent, so about one
 * gross in seven does not come home. {@link #roundTrips} says whether a particular one does, and
 * order entry uses it to warn the seller.
 *
 * @param cents the amount, in whole cents
 */
public record Money(long cents) implements Comparable<Money> {

    /** Nothing. */
    public static final Money ZERO = new Money(0);

    /** What a recognised agency keeps. Direct business has no commission. */
    public static final BigDecimal AGENCY_RATE = new BigDecimal("0.15");

    /** The precision the original arithmetic ran at: 28 significant digits, half even. */
    static final MathContext DIVISION = new MathContext(28, RoundingMode.HALF_EVEN);

    private static final Pattern AMOUNT = Pattern.compile("^\\$?\\s*(-?)([\\d,]+)(?:\\.(\\d{1,2}))?$");

    /** Decimal dollars, rounded half up to the cent the way an invoice rounds. */
    public static Money dollars(BigDecimal amount) {
        return new Money(toCents(amount));
    }

    /** Whole dollars. */
    public static Money dollars(long amount) {
        return dollars(BigDecimal.valueOf(amount));
    }

    /** Dollars written as a decimal, such as {@code "18.50"}. */
    public static Money dollars(String amount) {
        return dollars(new BigDecimal(amount));
    }

    /** {@code $1,250}, {@code 1250.00} and {@code -45.50} are all amounts. */
    public static Money parse(String text) {
        Matcher match = AMOUNT.matcher(text.strip());
        if (!match.matches()) {
            throw new MoneyError("'" + text + "' is not an amount");
        }
        String fraction = match.group(3) == null ? "0" : match.group(3);
        long whole = Long.parseLong(match.group(2).replace(",", ""));
        long cents = whole * 100 + Long.parseLong(padRight(fraction));
        return new Money(match.group(1).isEmpty() ? cents : -cents);
    }

    /** The sum of a column of amounts. */
    public static Money total(List<Money> amounts) {
        return new Money(amounts.stream().mapToLong(Money::cents).sum());
    }

    /** What a recognised agency keeps out of a gross amount. */
    public static Money commission(Money gross) {
        return commission(gross, AGENCY_RATE);
    }

    /** What the agency keeps out of a gross amount at {@code rate}. */
    public static Money commission(Money gross, BigDecimal rate) {
        return gross.scale(rate);
    }

    /** Gross less the standard commission. This is the direction that is exact. */
    public static Money toNet(Money gross) {
        return toNet(gross, AGENCY_RATE);
    }

    /** Gross less commission at {@code rate}. */
    public static Money toNet(Money gross, BigDecimal rate) {
        return gross.minus(commission(gross, rate));
    }

    /** Gross the net back up at the standard commission. Rounded, so not always reversible. */
    public static Money toGross(Money net) {
        return toGross(net, AGENCY_RATE);
    }

    /** Gross the net back up at {@code rate}. Rounded, so not always reversible. */
    public static Money toGross(Money net, BigDecimal rate) {
        if (rate.compareTo(BigDecimal.ONE) >= 0) {
            throw new MoneyError("a commission of everything cannot be grossed up");
        }
        return new Money(toCents(net.amount().divide(BigDecimal.ONE.subtract(rate), DIVISION)));
    }

    /** Whether netting down and grossing back up at the standard rate lands on the same cent. */
    public static boolean roundTrips(Money gross) {
        return roundTrips(gross, AGENCY_RATE);
    }

    /** Whether netting down and grossing back up at {@code rate} lands on the same cent. */
    public static boolean roundTrips(Money gross, BigDecimal rate) {
        return toGross(toNet(gross, rate), rate).equals(gross);
    }

    /** The amount in dollars, always to two places. */
    public BigDecimal amount() {
        return BigDecimal.valueOf(cents, 2);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(cents, other.cents));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(cents, other.cents));
    }

    public Money negate() {
        return new Money(-cents);
    }

    public Money abs() {
        return new Money(Math.abs(cents));
    }

    /** This amount, {@code count} times. Money multiplies by a count and never by a rate. */
    public Money times(long count) {
        return new Money(Math.multiplyExact(cents, count));
    }

    public boolean isZero() {
        return cents == 0;
    }

    public boolean isNegative() {
        return cents < 0;
    }

    /** A percentage of an amount, rounded to the cent, half up. */
    public Money scale(BigDecimal factor) {
        return new Money(toCents(amount().multiply(factor)));
    }

    /**
     * Split this amount by {@code weights} so the pieces add back to it exactly.
     *
     * <p>Largest remainder: everybody gets the floor of their share, then the leftover cents go
     * out one at a time to whoever was robbed most. A four week flight of $1,000.00 does not divide
     * by three markets, and somebody has to be a cent light rather than the invoice a cent wrong.
     */
    public List<Money> allocate(List<Integer> weights) {
        if (weights.isEmpty()) {
            throw new MoneyError("nothing to allocate to");
        }
        if (weights.stream().anyMatch(weight -> weight < 0)) {
            throw new MoneyError("a share cannot be negative");
        }
        long total = weights.stream().mapToLong(Integer::longValue).sum();
        if (total == 0) {
            throw new MoneyError("the shares are all zero");
        }
        long[] floors = new long[weights.size()];
        long handedOut = 0;
        for (int index = 0; index < floors.length; index++) {
            floors[index] = Math.floorDiv(cents * weights.get(index), total);
            handedOut += floors[index];
        }
        long remainder = cents - handedOut;
        List<Integer> order = IntStream.range(0, weights.size())
                .boxed()
                .sorted(Comparator.comparingLong((Integer index) -> -Math.floorMod(cents * weights.get(index), total))
                        .thenComparingInt(index -> index))
                .toList();
        long step = remainder >= 0 ? 1 : -1;
        for (int position = 0; position < Math.abs(remainder); position++) {
            floors[order.get(position % order.size())] += step;
        }
        List<Money> pieces = new ArrayList<>(floors.length);
        for (long piece : floors) {
            pieces.add(new Money(piece));
        }
        return List.copyOf(pieces);
    }

    /** An even split, which is {@link #allocate} with equal weights. */
    public List<Money> split(int ways) {
        if (ways < 1) {
            throw new MoneyError("cannot split fewer than one way");
        }
        return allocate(Collections.nCopies(ways, 1));
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(cents, other.cents);
    }

    /** {@code $1,250.00}, and {@code -$45.50} for a credit. */
    @Override
    public String toString() {
        String sign = cents < 0 ? "-" : "";
        long magnitude = Math.abs(cents);
        return String.format(Locale.ROOT, "%s$%,d.%02d", sign, magnitude / 100, magnitude % 100);
    }

    private static long toCents(BigDecimal dollars) {
        return dollars.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static String padRight(String fraction) {
        return fraction.length() == 1 ? fraction + "0" : fraction;
    }
}
