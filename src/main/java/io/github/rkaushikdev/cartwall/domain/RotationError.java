package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.ValidationError;

/** A rotation that cannot be run. */
public class RotationError extends ValidationError {

    public RotationError(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "bad_rotation";
    }
}
