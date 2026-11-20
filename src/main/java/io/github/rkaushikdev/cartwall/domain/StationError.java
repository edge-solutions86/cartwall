package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.ValidationError;

/** A signal that could not exist as described. */
public class StationError extends ValidationError {

    public StationError(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "bad_station";
    }
}
