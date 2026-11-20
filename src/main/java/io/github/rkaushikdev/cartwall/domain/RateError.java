package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** A rate that cannot be quoted. */
public class RateError extends ValidationError {

    public RateError(String message) {
        super(message);
    }

    public RateError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_rate";
    }
}
