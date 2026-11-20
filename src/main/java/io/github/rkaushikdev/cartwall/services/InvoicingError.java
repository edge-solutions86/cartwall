package io.github.rkaushikdev.cartwall.services;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** A month end run that could not be made. */
public class InvoicingError extends ValidationError {

    public InvoicingError(String message) {
        super(message);
    }

    public InvoicingError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_invoicing";
    }
}
