package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Every card ever filed, per station, so an old order can be repriced off its own card. */
public final class RateCardBook {

    private static final Comparator<RateCard> FILING_ORDER =
            Comparator.comparing(RateCard::station).thenComparing(RateCard::effective);

    private final List<RateCard> cards = new ArrayList<>();

    public void file(RateCard card) {
        for (RateCard filed : cards) {
            if (filed.station().equals(card.station()) && filed.effective().equals(card.effective())) {
                throw new RateError(
                        card.station() + " already has card " + filed.code() + " effective " + card.effective());
            }
        }
        cards.add(card);
        cards.sort(FILING_ORDER);
    }

    /** Every card, by station then effective date. */
    public List<RateCard> cards() {
        return Collections.unmodifiableList(cards);
    }

    /** The newest card for {@code station} that had taken effect by {@code on}. */
    public RateCard inForce(String station, LocalDate on) {
        RateCard found = null;
        for (RateCard card : cards) {
            if (card.station().equals(station) && !card.effective().isAfter(on)) {
                found = card;
            }
        }
        if (found == null) {
            throw new RateError(station + " has no card in force on " + on);
        }
        return found;
    }

    public Money rate(String station, LocalDate on, String daypart, ClassOfTime grade, SpotLength length) {
        return inForce(station, on).rate(daypart, grade, length);
    }

    /** Every card {@code station} ever had, oldest first. */
    public List<RateCard> history(String station) {
        return cards.stream().filter(card -> card.station().equals(station)).toList();
    }
}
