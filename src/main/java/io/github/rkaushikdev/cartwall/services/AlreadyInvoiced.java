package io.github.rkaushikdev.cartwall.services;

import io.github.rkaushikdev.cartwall.ConflictError;
import java.util.Map;

/** This order has an invoice for this month already. */
public class AlreadyInvoiced extends ConflictError {

    public AlreadyInvoiced(String message) {
        super(message);
    }

    public AlreadyInvoiced(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "already_invoiced";
    }
}
