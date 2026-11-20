package io.github.rkaushikdev.cartwall.domain;

/** What the business office will let an account do. */
public enum Credit {
    OPEN("open"),
    PREPAY("cash in advance"),
    HOLD("hold");

    private final String value;

    Credit(String value) {
        this.value = value;
    }

    /** The name stored in the database and sent over the API. */
    public String value() {
        return value;
    }

    public boolean maySchedule() {
        return this != HOLD;
    }

    public static Credit fromValue(String value) {
        for (Credit credit : values()) {
            if (credit.value.equals(value)) {
                return credit;
            }
        }
        throw new AccountError("'" + value + "' is not a credit standing");
    }
}
