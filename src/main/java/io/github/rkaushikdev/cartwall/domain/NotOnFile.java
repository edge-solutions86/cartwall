package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.LookupFailure;
import java.util.Map;

/**
 * Asked for an account nobody has opened.
 *
 * <p>Its own type rather than an {@link AccountError}: a caller asking for an account that is not
 * there has not sent bad input, it has asked about something that does not exist, and over HTTP
 * those are 404 and 422.
 */
public class NotOnFile extends LookupFailure {

    public NotOnFile(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "no_such_account";
    }
}
