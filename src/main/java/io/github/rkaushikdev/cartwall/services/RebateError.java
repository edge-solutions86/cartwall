package io.github.rkaushikdev.cartwall.services;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** A rebate run that cannot be made. */
public class RebateError extends ValidationError {

    public RebateError(String message) {
        super(message);
    }

    public RebateError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_rebate";
    }
}
