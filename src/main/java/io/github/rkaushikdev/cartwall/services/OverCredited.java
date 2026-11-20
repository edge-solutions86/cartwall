package io.github.rkaushikdev.cartwall.services;

import io.github.rkaushikdev.cartwall.ConflictError;
import java.util.Map;

/** More than is left on the invoice. */
public class OverCredited extends ConflictError {

    public OverCredited(String message) {
        super(message);
    }

    public OverCredited(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "over_credited";
    }
}
