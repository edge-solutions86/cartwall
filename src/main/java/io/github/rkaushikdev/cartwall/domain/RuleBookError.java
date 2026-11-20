package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.ValidationError;

/** A station that cannot be traded on the day asked about. */
public class RuleBookError extends ValidationError {

    public RuleBookError(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "bad_rulebook";
    }
}
