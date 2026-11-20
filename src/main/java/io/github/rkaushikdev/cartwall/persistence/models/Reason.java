package io.github.rkaushikdev.cartwall.persistence.models;

import io.github.rkaushikdev.cartwall.services.CreditError;

/**
 * Why a credit memo was written. A fixed list, because the month end report groups by it and
 * three spellings of "customer complaint" are three rows nobody wanted.
 */
public enum Reason {
    /** A spot that did not run and was billed anyway. */
    DID_NOT_RUN("did not run"),
    /** Ran, but not where it was sold, and the client would not accept it. */
    WRONG_DAYPART("wrong daypart"),
    /** The lowest unit charge moved after the invoice went out. */
    POLITICAL_REBATE("political rebate"),
    /** Billed at the wrong rate. */
    RATE_ERROR("rate error"),
    /** Everything else, which needs a note. */
    OTHER("other");

    private final String value;

    Reason(String value) {
        this.value = value;
    }

    /** The name stored in the database and printed on the report. */
    public String value() {
        return value;
    }

    public boolean needsANote() {
        return this == OTHER;
    }

    public static Reason fromValue(String value) {
        for (Reason reason : values()) {
            if (reason.value.equals(value)) {
                return reason;
            }
        }
        throw new CreditError("'" + value + "' is not a reason for a credit");
    }
}
