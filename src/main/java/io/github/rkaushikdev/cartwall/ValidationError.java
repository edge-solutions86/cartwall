package io.github.rkaushikdev.cartwall;

import java.util.Map;

/**
 * The input could not be what it says it is.
 *
 * <p>Every refusal raised while building a domain value is one of these, so catching this type
 * around order entry catches all of them.
 */
public class ValidationError extends CartwallError {

    public ValidationError(String message) {
        super(message);
    }

    public ValidationError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "invalid";
    }
}
