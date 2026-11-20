package io.github.rkaushikdev.cartwall.billing;

import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.SpotLength;

/**
 * One order line's month, which is what the seller reads.
 *
 * @param line the order line
 * @param station the station it was bought on
 * @param daypart the daypart it was bought in
 * @param length the sold length
 * @param rate the rate on the order
 * @param spots how many aired
 */
public record Summary(int line, String station, String daypart, SpotLength length, Money rate, int spots) {

    public Money gross() {
        return rate.times(spots);
    }

    @Override
    public String toString() {
        return "line " + line + " " + station + " " + daypart + " " + spots + " at " + rate;
    }
}
