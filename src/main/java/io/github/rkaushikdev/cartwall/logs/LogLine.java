package io.github.rkaushikdev.cartwall.logs;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import java.util.Objects;
import java.util.Optional;

/**
 * One element of the log, at the second it hits the air.
 *
 * @param at when it airs
 * @param breakNumber the break it is in
 * @param element what it is
 * @param length how long it runs
 * @param order the order, for a spot
 * @param line the line, for a spot
 * @param advertiser the advertiser, for a spot
 * @param cart the cart, for a spot that has copy
 * @param grade the class of time, for a spot
 */
public record LogLine(
        AirTime at,
        int breakNumber,
        Element element,
        Duration length,
        String order,
        int line,
        String advertiser,
        String cart,
        Optional<ClassOfTime> grade) {

    public LogLine {
        Objects.requireNonNull(grade, "grade");
    }

    /** A line that is not a spot: a legal ID, a network break, a promo or a hole. */
    public LogLine(AirTime at, int breakNumber, Element element, Duration length) {
        this(at, breakNumber, element, length, "", 0, "", "", Optional.empty());
    }

    public int hour() {
        return at.hour();
    }

    public boolean isSpot() {
        return element == Element.SPOT;
    }

    /** The line the way the printed log shows it. */
    @Override
    public String toString() {
        String stamp = "%11s".formatted(at.twelveHour());
        if (element == Element.SPOT) {
            return "%s  %6s  %-6s %s".formatted(stamp, length, cart.isEmpty() ? "-" : cart, advertiser);
        }
        return "%s  %6s  %s".formatted(stamp, length, element.value());
    }
}
