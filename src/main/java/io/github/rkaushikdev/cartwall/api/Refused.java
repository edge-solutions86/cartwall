package io.github.rkaushikdev.cartwall.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;

/**
 * A value in a request body that is the wrong kind of thing, refused with the sentence a caller is
 * shown. Jackson adds where in the body it was found as the refusal travels out.
 */
public final class Refused extends JsonMappingException {

    private static final long serialVersionUID = 1L;

    public Refused(JsonParser in, String why) {
        super(in, why);
    }
}
