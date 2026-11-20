package io.github.rkaushikdev.cartwall;

import java.util.Map;

/**
 * Well formed, and the state says no.
 *
 * <p>A separation violation and a sold out break are both this. It is deliberately not a {@link
 * ValidationError}: nothing about the request was wrong.
 */
public class ConflictError extends CartwallError {

    public ConflictError(String message) {
        super(message);
    }

    public ConflictError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "conflict";
    }
}
