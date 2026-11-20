package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.ValidationError;

/** A political order that cannot be taken as described. */
public class PoliticalError extends ValidationError {

    public PoliticalError(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "bad_political";
    }
}
