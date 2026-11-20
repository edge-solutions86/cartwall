package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.domain.Violation;
import java.util.List;

/**
 * One hand placed spot and why it was allowed.
 *
 * @param landed where it went
 * @param override the written reason, when the traffic director overrode a rule
 * @param broke the rules the override broke
 */
public record Pinned(Landed landed, String override, List<Violation> broke) {

    public Pinned {
        broke = List.copyOf(broke);
    }

    public boolean wasForced() {
        return !override.isEmpty();
    }

    @Override
    public String toString() {
        if (!wasForced()) {
            return "pinned to " + landed;
        }
        return "pinned to " + landed + " over " + broke.size() + " rule(s): " + override;
    }
}
