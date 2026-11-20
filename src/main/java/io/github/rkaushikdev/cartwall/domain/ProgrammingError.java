package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.ValidationError;

/** A grid that cannot be run. */
public class ProgrammingError extends ValidationError {

    public ProgrammingError(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "bad_programming";
    }
}
