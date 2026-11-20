package io.github.rkaushikdev.cartwall.persistence;

import io.github.rkaushikdev.cartwall.ValidationError;

/** A unit of work used after it was finished with. */
public class UnitOfWorkError extends ValidationError {

    public UnitOfWorkError(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "bad_unit_of_work";
    }
}
