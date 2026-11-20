package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.SpotLength;

/**
 * One class of political time bought, at one rate.
 *
 * @param station the station
 * @param daypart the daypart code
 * @param grade the class of time
 * @param length the sold length
 * @param rate the rate charged
 * @param spots how many
 */
public record Bought(String station, String daypart, ClassOfTime grade, SpotLength length, Money rate, int spots) {

    public Money value() {
        return rate.times(spots);
    }

    @Override
    public String toString() {
        return station + " " + daypart + " " + grade.value() + " " + length + " " + spots + " at " + rate;
    }
}
