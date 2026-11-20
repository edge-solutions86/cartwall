package io.github.rkaushikdev.cartwall.domain;

import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Whose commercial it is. Separation rules are about advertisers, and so is the public file.
 *
 * <p>An advertiser can carry more than one conflict code, because the grocery store sells
 * gasoline.
 *
 * @param code the account code
 * @param name the advertiser's name
 * @param categories its conflict codes
 * @param agency the agency that buys for it; empty for direct business
 * @param credit what the business office lets it do
 * @param parent set when this account is a store of a chain that buys as one advertiser
 */
public record Advertiser(
        String code,
        String name,
        Set<Category> categories,
        Optional<String> agency,
        Credit credit,
        Optional<String> parent) {

    public Advertiser {
        categories = Collections.unmodifiableSet(new TreeSet<>(categories));
        Objects.requireNonNull(agency, "agency");
        Objects.requireNonNull(parent, "parent");
        if (!Agency.CODE.matcher(code).matches()) {
            throw new AccountError("'" + code + "' is not an account code");
        }
        if (name == null || name.isEmpty()) {
            throw new AccountError("advertiser " + code + " has no name");
        }
        if (parent.filter(code::equals).isPresent()) {
            throw new AccountError(code + " cannot be its own parent");
        }
    }

    /** A direct advertiser on open credit with no conflict codes. */
    public static Advertiser build(String code, String name) {
        return build(code, name, "");
    }

    /** A direct advertiser on open credit, with its codes written comma separated. */
    public static Advertiser build(String code, String name, String categories) {
        Set<Category> codes = new TreeSet<>();
        Arrays.stream(categories.split(","))
                .map(String::strip)
                .filter(piece -> !piece.isEmpty())
                .map(Category::new)
                .forEach(codes::add);
        return new Advertiser(code, name, codes, Optional.empty(), Credit.OPEN, Optional.empty());
    }

    public Advertiser withAgency(String agency) {
        return new Advertiser(code, name, categories, Optional.ofNullable(agency), credit, parent);
    }

    public Advertiser withCredit(Credit credit) {
        return new Advertiser(code, name, categories, agency, credit, parent);
    }

    public Advertiser withParent(String parent) {
        return new Advertiser(code, name, categories, agency, credit, Optional.ofNullable(parent));
    }

    public boolean isDirect() {
        return agency.isEmpty();
    }

    /** Two advertisers conflict when any of their codes touch, and always with themselves. */
    public boolean conflictsWith(Advertiser other) {
        if (code.equals(other.code)) {
            return true;
        }
        for (Category mine : categories) {
            for (Category theirs : other.categories) {
                if (mine.conflictsWith(theirs)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The broadest code each conflict runs through, for the error message. */
    public Set<Category> sharedCategories(Advertiser other) {
        Set<Category> found = new TreeSet<>();
        for (Category mine : categories) {
            for (Category theirs : other.categories) {
                if (mine.covers(theirs)) {
                    found.add(mine);
                } else if (theirs.covers(mine)) {
                    found.add(theirs);
                }
            }
        }
        return Collections.unmodifiableSet(found);
    }

    @Override
    public String toString() {
        return name;
    }
}
