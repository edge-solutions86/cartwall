package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** An order that cannot be written as described. */
public class OrderError extends ValidationError {

    public OrderError(String message) {
        super(message);
    }

    public OrderError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_order";
    }
}
