package io.github.rkaushikdev.cartwall.api;

import io.github.rkaushikdev.cartwall.Details;

/**
 * The person behind a request, for the audit trail as much as for the gate. A key carries a person
 * and a role, because a key that only says "authorised" gives the trail nothing to record.
 */
public record Caller(String name, Role role) {

    /** Let the caller through at {@code wanted} or above, or refuse saying who and why. */
    public void require(Role wanted) {
        if (!role.can(wanted)) {
            throw new NotAllowed(
                    name + " is " + role.label() + " and this needs " + wanted.label(),
                    Details.of("actor", name, "has", role.label(), "needs", wanted.label()));
        }
    }

    @Override
    public String toString() {
        return name + " (" + role.label() + ")";
    }
}
