package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** An avails report that cannot be drawn as asked. */
public class ReportError extends ValidationError {

    public ReportError(String message) {
        super(message);
    }

    public ReportError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_report";
    }
}
