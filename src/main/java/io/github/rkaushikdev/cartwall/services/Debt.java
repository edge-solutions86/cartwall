package io.github.rkaushikdev.cartwall.services;

import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import io.github.rkaushikdev.cartwall.scheduling.LineKey;
import java.time.LocalDate;

/**
 * One spot owed to one line, whatever the cause.
 *
 * @param order the order number
 * @param line the line number
 * @param advertiser the advertiser's code
 * @param station the station it was owed on
 * @param daypart the daypart it was sold in
 * @param grade the class of time
 * @param length the sold length
 * @param rate what the spot was worth on the day it was missed, not today
 * @param day the day it was owed for
 * @param cause why it is owed
 */
public record Debt(
        String order,
        int line,
        String advertiser,
        String station,
        String daypart,
        ClassOfTime grade,
        SpotLength length,
        Money rate,
        LocalDate day,
        Cause cause) {

    public LineKey key() {
        return new LineKey(order, line);
    }

    @Override
    public String toString() {
        return order + "-" + line + " " + station + " " + day + " " + cause.value() + ", worth " + rate;
    }
}
