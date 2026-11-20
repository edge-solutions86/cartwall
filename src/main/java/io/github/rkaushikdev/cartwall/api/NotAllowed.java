package io.github.rkaushikdev.cartwall.api;

import io.github.rkaushikdev.cartwall.CartwallError;
import java.util.Map;

/** A caller who may not do this. */
public class NotAllowed extends CartwallError {

    public NotAllowed(String message) {
        super(message);
    }

    public NotAllowed(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "not_allowed";
    }
}
