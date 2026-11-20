package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Duration;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything a separation rule needs to know about one placed spot.
 *
 * @param day the broadcast day
 * @param at when it airs
 * @param breakNumber the break it sits in
 * @param advertiser whose spot it is
 * @param cart the cart it plays, or empty before copy is assigned
 * @param agency the agency that placed it, when there is one
 * @param fixed set on a spot the traffic director pinned; it is checked, never moved
 */
public record Candidate(
        LocalDate day,
        AirTime at,
        int breakNumber,
        Advertiser advertiser,
        String cart,
        Optional<String> agency,
        boolean fixed) {

    public Candidate {
        Objects.requireNonNull(agency, "agency");
    }

    /** A spot with no cart, no agency, and nothing pinned. */
    public Candidate(LocalDate day, AirTime at, int breakNumber, Advertiser advertiser) {
        this(day, at, breakNumber, advertiser, "", Optional.empty(), false);
    }

    public int hour() {
        return at.hour();
    }

    /** How far apart two spots on the same day are. Never negative. */
    public Duration gapTo(Candidate other) {
        if (!day.equals(other.day)) {
            throw new SeparationError("two spots on different days have no gap");
        }
        return at.isBefore(other.at) ? other.at.minus(at) : at.minus(other.at);
    }

    @Override
    public String toString() {
        return advertiser.code() + " at " + at.twelveHour();
    }
}
