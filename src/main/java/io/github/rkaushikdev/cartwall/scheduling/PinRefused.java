package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.ConflictError;
import java.util.Map;

/** The named break will not take this spot. */
public class PinRefused extends ConflictError {

    public PinRefused(String message) {
        super(message);
    }

    public PinRefused(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "pin_refused";
    }
}
