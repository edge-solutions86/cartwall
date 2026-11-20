package io.github.rkaushikdev.cartwall.domain;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A dotted conflict code, such as {@code AUTO.DOMESTIC.FORD}. Broader codes catch narrower ones.
 *
 * <p>Two codes conflict when one is a prefix of the other, so the Ford dealer conflicts with the
 * Chevrolet dealer through {@code AUTO.DOMESTIC}, with the Toyota dealer through {@code AUTO}, and
 * not with the furniture store at all.
 *
 * @param path the dotted code
 */
public record Category(String path) implements Comparable<Category> {

    private static final Pattern CATEGORY = Pattern.compile("^[A-Z][A-Z0-9]*(?:\\.[A-Z][A-Z0-9]*)*$");

    public Category {
        if (!CATEGORY.matcher(path).matches()) {
            throw new AccountError("'" + path + "' is not a conflict code");
        }
    }

    public List<String> parts() {
        return List.of(path.split("\\."));
    }

    public int depth() {
        return parts().size();
    }

    /** The next code up, or nothing for a top level code. */
    public Optional<Category> parent() {
        if (depth() == 1) {
            return Optional.empty();
        }
        List<String> parts = parts();
        return Optional.of(new Category(String.join(".", parts.subList(0, parts.size() - 1))));
    }

    /** True when {@code other} is this code or sits underneath it. */
    public boolean covers(Category other) {
        List<String> theirs = other.parts();
        return theirs.size() >= depth() && theirs.subList(0, depth()).equals(parts());
    }

    public boolean conflictsWith(Category other) {
        return covers(other) || other.covers(this);
    }

    @Override
    public int compareTo(Category other) {
        return path.compareTo(other.path);
    }

    @Override
    public String toString() {
        return path;
    }
}
