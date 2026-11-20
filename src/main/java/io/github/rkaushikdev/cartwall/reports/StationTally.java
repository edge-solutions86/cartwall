package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.Money;

/**
 * Spots and money on one station, for a client who bought several.
 *
 * @param count how many spots
 * @param worth what they were billed at
 */
public record StationTally(int count, Money worth) {

    StationTally add(Money rate) {
        return new StationTally(count + 1, worth.plus(rate));
    }
}
