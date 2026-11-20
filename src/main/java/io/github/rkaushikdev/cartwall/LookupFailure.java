package io.github.rkaushikdev.cartwall;

import java.util.Map;

/** Asked for something that is not on file. */
public class LookupFailure extends CartwallError {

    public LookupFailure(String message) {
        super(message);
    }

    public LookupFailure(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "not_found";
    }
}
