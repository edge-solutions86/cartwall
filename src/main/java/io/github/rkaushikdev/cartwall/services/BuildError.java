package io.github.rkaushikdev.cartwall.services;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** A log build that could not be started. */
public class BuildError extends ValidationError {

    public BuildError(String message) {
        super(message);
    }

    public BuildError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_build";
    }
}
