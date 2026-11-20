package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * One spot, as it actually went out: a line of an affidavit.
 *
 * @param day the date it ran
 * @param at the time off the as-run, as printed
 * @param station the station
 * @param length the sold length
 * @param cart the cart that played
 * @param rate what it was billed at
 */
public record Ran(LocalDate day, String at, String station, SpotLength length, String cart, Money rate) {

    static final DateTimeFormatter SWORN_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ROOT);

    public Ran {
        if (at == null || at.isEmpty()) {
            throw new AffidavitError(
                    "a spot on " + day + " has no time and cannot be sworn to", Details.of("day", day.toString()));
        }
        if (station == null || station.isEmpty()) {
            throw new AffidavitError("a spot on " + day + " names no station");
        }
    }

    @Override
    public String toString() {
        return "%s  %10s  %-8s %s  %s".formatted(SWORN_DATE.format(day), at, station, length, cart);
    }
}
