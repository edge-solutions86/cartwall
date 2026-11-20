package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.ValidationError;

/** A daypart that could not be sold as described. */
public class DaypartError extends ValidationError {

    public DaypartError(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "bad_daypart";
    }
}
