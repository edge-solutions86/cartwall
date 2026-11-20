package io.github.rkaushikdev.cartwall.billing;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** An invoice that cannot be raised. */
public class InvoiceError extends ValidationError {

    public InvoiceError(String message) {
        super(message);
    }

    public InvoiceError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_invoice";
    }
}
