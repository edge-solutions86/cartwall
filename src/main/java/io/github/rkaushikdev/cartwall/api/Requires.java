package io.github.rkaushikdev.cartwall.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The role a route needs. Checked before the request body is read, so a caller who may not be
 * here hears that rather than what was wrong with what they sent.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Requires {

    Role value();
}
