package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.ValidationError;

/** A separation rule that cannot be filed. */
public class SeparationError extends ValidationError {

    public SeparationError(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "bad_separation";
    }
}
