package io.github.rkaushikdev.cartwall.services;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** A series that cannot be drawn from. */
public class NumberingError extends ValidationError {

    public NumberingError(String message) {
        super(message);
    }

    public NumberingError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_numbering";
    }
}
