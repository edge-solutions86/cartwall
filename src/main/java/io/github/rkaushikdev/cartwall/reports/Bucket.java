package io.github.rkaushikdev.cartwall.reports;

/**
 * The four ages of an open invoice, and the day count each one opens at.
 *
 * <p>The boundaries are inclusive at the low end, which matters more than it sounds: an invoice
 * raised exactly thirty days ago is in the thirty day bucket, not in current, and a station that
 * rounds the other way reports a month that looks a month better than it is.
 */
public enum Bucket {
    CURRENT(0, "current"),
    THIRTY(30, "30 days"),
    SIXTY(60, "60 days"),
    NINETY(90, "90 plus");

    private final int opens;
    private final String label;

    Bucket(int opens, String label) {
        this.opens = opens;
        this.label = label;
    }

    /** The age, in days, the bucket opens at. */
    public int opens() {
        return opens;
    }

    /** How the office says it. */
    public String label() {
        return label;
    }

    /** Which bucket an age falls in. Inclusive at the low end. */
    public static Bucket of(long days) {
        if (days >= NINETY.opens) {
            return NINETY;
        }
        if (days >= SIXTY.opens) {
            return SIXTY;
        }
        if (days >= THIRTY.opens) {
            return THIRTY;
        }
        return CURRENT;
    }
}
