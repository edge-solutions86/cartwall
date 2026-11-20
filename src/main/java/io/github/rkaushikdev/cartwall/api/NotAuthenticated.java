package io.github.rkaushikdev.cartwall.api;

import io.github.rkaushikdev.cartwall.CartwallError;
import java.util.Map;

/** A caller we do not recognise. */
public class NotAuthenticated extends CartwallError {

    public NotAuthenticated(String message) {
        super(message);
    }

    public NotAuthenticated(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "not_authenticated";
    }
}
