package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.ValidationError;

/** A clock that could not be run as drawn. */
public class ClockError extends ValidationError {

    public ClockError(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "bad_clock";
    }
}
