package io.github.rkaushikdev.cartwall.services;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** A gather that cannot be run as asked. */
public class OwingError extends ValidationError {

    public OwingError(String message) {
        super(message);
    }

    public OwingError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_owing";
    }
}
