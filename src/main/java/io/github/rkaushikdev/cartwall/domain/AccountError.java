package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** An account that cannot be set up as described. */
public class AccountError extends ValidationError {

    public AccountError(String message) {
        super(message);
    }

    public AccountError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_account";
    }
}
