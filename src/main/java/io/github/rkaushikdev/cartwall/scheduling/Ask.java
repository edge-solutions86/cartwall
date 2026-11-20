package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.domain.Advertiser;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import java.util.Objects;
import java.util.Optional;

/**
 * One spot that needs somewhere to go.
 *
 * @param order the order number
 * @param line the line number
 * @param advertiser whose spot it is
 * @param daypart the daypart it was sold in
 * @param length the sold length
 * @param grade the class of time
 * @param cart the cart it plays, or empty before copy is assigned
 * @param agency the agency that placed it, when there is one
 */
public record Ask(
        String order,
        int line,
        Advertiser advertiser,
        String daypart,
        SpotLength length,
        ClassOfTime grade,
        String cart,
        Optional<String> agency) {

    public Ask {
        Objects.requireNonNull(agency, "agency");
        if (order == null || order.isEmpty()) {
            throw new PlacementError("an ask names no order");
        }
        if (line < 1) {
            throw new PlacementError("order " + order + " line " + line + " is not a line number");
        }
    }

    /** An ask with no copy yet and no agency. */
    public Ask(String order, int line, Advertiser advertiser, String daypart, SpotLength length, ClassOfTime grade) {
        this(order, line, advertiser, daypart, length, grade, "", Optional.empty());
    }

    public LineKey key() {
        return new LineKey(order, line);
    }

    /** The same ask, playing {@code cart}. */
    public Ask withCart(String cart) {
        return new Ask(order, line, advertiser, daypart, length, grade, cart, agency);
    }

    @Override
    public String toString() {
        return order + "-" + line + " " + advertiser.code() + " " + length + " " + daypart;
    }
}
