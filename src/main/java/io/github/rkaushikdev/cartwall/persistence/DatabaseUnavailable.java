package io.github.rkaushikdev.cartwall.persistence;

import java.sql.SQLException;

/**
 * The database would not answer. Not one of the package's refusals: nothing about the request was
 * wrong and the state said nothing, the machine simply was not there.
 */
public class DatabaseUnavailable extends RuntimeException {

    public DatabaseUnavailable(String url, SQLException cause) {
        super(url + ": " + cause.getMessage(), cause);
    }
}
