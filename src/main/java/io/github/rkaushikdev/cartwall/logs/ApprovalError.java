package io.github.rkaushikdev.cartwall.logs;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** An approval that cannot be recorded. */
public class ApprovalError extends ValidationError {

    public ApprovalError(String message) {
        super(message);
    }

    public ApprovalError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_approval";
    }
}
