package io.github.rkaushikdev.cartwall.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * One debt and every offer made against it, oldest offer first.
 *
 * <p>This pairing is the whole reason the ledger is not two lists. Four spots owed on the same
 * line, the same day and for the same reason are four debts that compare equal, so a ledger that
 * matched offers to debts by value closed all four the moment one makegood was accepted.
 */
public final class LedgerEntry {

    private final Debt debt;
    private final List<Makegood> offers;

    public LedgerEntry(Debt debt, List<Makegood> offers) {
        this.debt = debt;
        this.offers = new ArrayList<>(offers);
    }

    public Debt debt() {
        return debt;
    }

    public List<Makegood> offers() {
        return Collections.unmodifiableList(offers);
    }

    /** Record another offer against this debt. */
    public void addOffer(Makegood made) {
        offers.add(made);
    }

    public Optional<Makegood> accepted() {
        return offers.stream().filter(made -> made.state() == Offer.ACCEPTED).findFirst();
    }

    public Optional<Makegood> open() {
        return offers.stream().filter(made -> !made.state().isSettled()).findFirst();
    }

    /** Still owed until a makegood against this spot has been accepted. */
    public boolean isOwed() {
        return accepted().isEmpty();
    }

    @Override
    public String toString() {
        return debt + " (" + accepted().map(Makegood::toString).orElse("nothing accepted") + ")";
    }
}
