package io.github.rkaushikdev.cartwall.domain;

/**
 * One cart and how much of the rotation it gets.
 *
 * @param cart the cart number
 * @param weight its share, at least one
 */
public record Slice(String cart, int weight) {

    public Slice {
        if (cart == null || cart.isEmpty()) {
            throw new RotationError("a rotation slice needs a cart");
        }
        if (weight < 1) {
            throw new RotationError("cart " + cart + " is weighted " + weight);
        }
    }

    /** A cart with a weight of one. */
    public Slice(String cart) {
        this(cart, 1);
    }

    @Override
    public String toString() {
        return weight == 1 ? cart : cart + " x" + weight;
    }
}
