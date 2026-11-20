package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** A pin that cannot be made. */
public class PinError extends ValidationError {

    public PinError(String message) {
        super(message);
    }

    public PinError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_pin";
    }
}
