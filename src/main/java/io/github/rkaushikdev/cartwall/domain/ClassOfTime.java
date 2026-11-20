package io.github.rkaushikdev.cartwall.domain;

/**
 * What the buyer is promised about whether the spot stays where it was sold.
 *
 * <p>The same thirty in the same daypart is three different prices: fixed runs where it was sold
 * and cannot be moved; preemptible with notice can be bumped if the station gives two weeks;
 * immediately preemptible can be bumped the morning of, which is why a station can sell out and
 * still take a political order.
 */
public enum ClassOfTime {
    FIXED("fixed", 3),
    NOTICE("preemptible with notice", 2),
    IMMEDIATE("immediately preemptible", 1);

    private final String value;
    private final int rank;

    ClassOfTime(String value, int rank) {
        this.value = value;
        this.rank = rank;
    }

    /** The name stored in the database and sent over the API. */
    public String value() {
        return value;
    }

    public boolean mayBePreempted() {
        return this != FIXED;
    }

    /** Days of warning owed before this class can be bumped. */
    public int noticeDays() {
        return this == NOTICE ? 14 : 0;
    }

    /** Higher outbids lower when two orders want the same unit. */
    public int rank() {
        return rank;
    }

    public static ClassOfTime fromValue(String value) {
        for (ClassOfTime grade : values()) {
            if (grade.value.equals(value)) {
                return grade;
            }
        }
        throw new RateError("'" + value + "' is not a class of time");
    }
}
