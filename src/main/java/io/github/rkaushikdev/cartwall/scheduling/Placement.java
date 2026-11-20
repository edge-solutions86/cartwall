package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.SpotLength;

/**
 * One spot sitting in one break.
 *
 * @param order the order number
 * @param line the line number
 * @param advertiser the advertiser's code
 * @param length the sold length
 * @param grade the class of time it was sold at
 * @param cart the cart it plays, or empty before copy is assigned
 * @param fixed pinned by the traffic director; the builder may not move it
 */
public record Placement(
        String order, int line, String advertiser, SpotLength length, ClassOfTime grade, String cart, boolean fixed) {

    public Placement {
        if (order == null || order.isEmpty()) {
            throw new InventoryError("a placement names no order");
        }
        if (line < 1) {
            throw new InventoryError("order " + order + " line " + line + " is not a line number");
        }
    }

    /** A movable spot with no copy assigned yet. */
    public Placement(String order, int line, String advertiser, SpotLength length, ClassOfTime grade) {
        this(order, line, advertiser, length, grade, "", false);
    }

    /** A movable spot playing {@code cart}. */
    public Placement(String order, int line, String advertiser, SpotLength length, ClassOfTime grade, String cart) {
        this(order, line, advertiser, length, grade, cart, false);
    }

    public LineKey key() {
        return new LineKey(order, line);
    }

    @Override
    public String toString() {
        return order + "-" + line + " " + advertiser + " " + length;
    }
}
