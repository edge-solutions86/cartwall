package io.github.rkaushikdev.cartwall.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Which cart runs in which spot when a line has more than one piece of copy.
 *
 * <p>The rotation is not a shuffle and it is not a counter. It is a smooth weighted round robin:
 * each cart carries a running position of {@code (used + 1/2) / weight}, and the next spot goes to
 * whichever cart has the smallest one. Equal weights give a strict cycle; two to one gives A B A A
 * B A A B. The positions are compared as exact fractions, because the tie between two carts of
 * equal weight has to break the same way on every machine and in every rerun.
 *
 * @param slices the carts and their weights, in the order written
 * @param kind even, weighted or sequential
 */
public record Rotation(List<Slice> slices, RotationKind kind) {

    public Rotation {
        slices = List.copyOf(slices);
        if (slices.isEmpty()) {
            throw new RotationError("a rotation needs at least one cart");
        }
        Set<String> seen = new HashSet<>();
        Set<String> repeated = new TreeSet<>();
        for (Slice slice : slices) {
            if (!seen.add(slice.cart())) {
                repeated.add(slice.cart());
            }
        }
        if (!repeated.isEmpty()) {
            throw new RotationError(String.join(", ", repeated) + " is in the rotation twice");
        }
        if (kind == RotationKind.EVEN && slices.stream().anyMatch(slice -> slice.weight() != 1)) {
            throw new RotationError("an even rotation cannot carry weights");
        }
    }

    public static Rotation even(String... carts) {
        return new Rotation(Arrays.stream(carts).map(Slice::new).toList(), RotationKind.EVEN);
    }

    /** Carts and their weights, in the order written. */
    public static Rotation weighted(Slice... slices) {
        return new Rotation(List.of(slices), RotationKind.WEIGHTED);
    }

    public static Rotation sequential(String... carts) {
        return new Rotation(Arrays.stream(carts).map(Slice::new).toList(), RotationKind.SEQUENTIAL);
    }

    public List<String> carts() {
        return slices.stream().map(Slice::cart).toList();
    }

    public int totalWeight() {
        return slices.stream().mapToInt(Slice::weight).sum();
    }

    /** Which cart runs in each of {@code count} spots, in air order. */
    public List<String> assign(int count) {
        if (count < 0) {
            throw new RotationError("cannot assign a negative number of spots");
        }
        if (kind == RotationKind.SEQUENTIAL) {
            List<String> chosen = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                chosen.add(slices.get(index % slices.size()).cart());
            }
            return List.copyOf(chosen);
        }
        return smooth(count);
    }

    private List<String> smooth(int count) {
        long[] used = new long[slices.size()];
        List<String> chosen = new ArrayList<>(count);
        for (int spot = 0; spot < count; spot++) {
            int best = 0;
            for (int index = 1; index < slices.size(); index++) {
                if (positionBefore(index, best, used)) {
                    best = index;
                }
            }
            used[best]++;
            chosen.add(slices.get(best).cart());
        }
        return List.copyOf(chosen);
    }

    /**
     * Whether cart {@code one} sits strictly ahead of cart {@code other}: compares {@code (2u+1)/2w}
     * exactly by cross multiplying. A tie keeps the earlier cart, which is the lower index.
     */
    private boolean positionBefore(int one, int other, long[] used) {
        long left = (2 * used[one] + 1) * (2L * slices.get(other).weight());
        long right = (2 * used[other] + 1) * (2L * slices.get(one).weight());
        return left < right;
    }

    /** How many spots each cart ends up with over {@code count}, in rotation order. */
    public Map<String, Integer> shares(int count) {
        Map<String, Integer> tally = new LinkedHashMap<>();
        carts().forEach(cart -> tally.put(cart, 0));
        assign(count).forEach(cart -> tally.merge(cart, 1, Integer::sum));
        return tally;
    }

    /** The most times one cart runs back to back over {@code count} spots. */
    public int longestRun(int count) {
        int longest = 0;
        int run = 0;
        String previous = null;
        for (String cart : assign(count)) {
            run = cart.equals(previous) ? run + 1 : 1;
            previous = cart;
            longest = Math.max(longest, run);
        }
        return longest;
    }

    /** Drop a cart, for when production kills one mid flight. */
    public Rotation without(String cart) {
        List<Slice> kept =
                slices.stream().filter(slice -> !slice.cart().equals(cart)).toList();
        if (kept.isEmpty()) {
            throw new RotationError("dropping " + cart + " would leave the line with no copy");
        }
        return new Rotation(kept, kind);
    }

    public int size() {
        return slices.size();
    }

    public boolean contains(String cart) {
        return carts().contains(cart);
    }

    @Override
    public String toString() {
        return kind.value() + ": " + slices.stream().map(Slice::toString).collect(Collectors.joining(", "));
    }
}
