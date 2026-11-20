package io.github.rkaushikdev.cartwall.logs;

/**
 * One reason a log cannot be approved, or one thing worth saying about it.
 *
 * @param code what kind of problem, such as {@code missing_copy}
 * @param where where on the log
 * @param detail what is wrong there
 */
public record Problem(String code, String where, String detail) {

    @Override
    public String toString() {
        return code + " " + where + ": " + detail;
    }
}
