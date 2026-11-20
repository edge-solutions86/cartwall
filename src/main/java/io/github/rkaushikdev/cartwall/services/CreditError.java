package io.github.rkaushikdev.cartwall.services;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** A credit that cannot be written as asked. */
public class CreditError extends ValidationError {

    public CreditError(String message) {
        super(message);
    }

    public CreditError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_credit";
    }
}
