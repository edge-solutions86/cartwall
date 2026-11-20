package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A sold length, in whole seconds. Only the lengths on the card can be sold.
 *
 * <p>Seconds and units are two different limits and they are easy to confuse. A three minute
 * break holds a hundred and eighty seconds of audio and not one tenth more. A break sold as six
 * units holds six spots; a sixty takes two of them and a ten still takes one.
 *
 * @param seconds the length on the order
 */
public record SpotLength(int seconds) implements Comparable<SpotLength> {

    /** What the rate card will quote. Anything else has to be sold as a special. */
    public static final List<Integer> SELLABLE_SECONDS = List.of(10, 15, 20, 30, 45, 60, 90, 120);

    /** A unit is a thirty. Everything shorter still costs a whole one. */
    public static final int SECONDS_IN_A_UNIT = 30;

    /** How far a cut may run from its sold length before production has to fix it. */
    public static final Duration TOLERANCE = new Duration(5);

    public static final SpotLength TEN = new SpotLength(10);
    public static final SpotLength THIRTY = new SpotLength(30);
    public static final SpotLength SIXTY = new SpotLength(60);

    public SpotLength {
        if (!SELLABLE_SECONDS.contains(seconds)) {
            String sellable =
                    SELLABLE_SECONDS.stream().map(value -> ":" + value).collect(Collectors.joining(", "));
            throw new SpotError(":" + seconds + " is not sold here, only " + sellable);
        }
    }

    /** {@code :30}, {@code 1:00}: anything {@link Duration#parse} reads, rounded up to the second. */
    public static SpotLength parse(String text) {
        return new SpotLength(Duration.parse(text).wholeSeconds());
    }

    /** Units of inventory this takes, rounded up. A ten still costs one. */
    public int units() {
        return Math.ceilDiv(seconds, SECONDS_IN_A_UNIT);
    }

    /** The sold length as air. */
    public Duration air() {
        return Duration.ofSeconds(seconds);
    }

    /** Whether a cut of this running time may be filed against this length. */
    public boolean fits(Duration actual) {
        Duration sold = air();
        Duration low = new Duration(Math.max(sold.tenths() - TOLERANCE.tenths(), 0));
        return low.compareTo(actual) <= 0 && actual.compareTo(sold.plus(TOLERANCE)) <= 0;
    }

    @Override
    public int compareTo(SpotLength other) {
        return Integer.compare(seconds, other.seconds);
    }

    @Override
    public String toString() {
        return air().toString();
    }
}
