package io.github.rkaushikdev.cartwall.domain;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole cluster's filed tables, and the maker of rule books.
 *
 * <p>Nothing is defaulted except an empty separation book. A station with no separation rules
 * filed places spots beside anything, and that is a decision somebody has to have made.
 */
public final class House {

    private final Cluster cluster;
    private final AccountBook accounts;
    private final RateCardBook cards;
    private final Map<String, DaypartGrid> dayparts;
    private final Map<String, ClockGrid> programming;
    private final Map<String, SeparationBook> separation;

    public House(
            Cluster cluster,
            AccountBook accounts,
            RateCardBook cards,
            Map<String, DaypartGrid> dayparts,
            Map<String, ClockGrid> programming,
            Map<String, SeparationBook> separation) {
        this.cluster = cluster;
        this.accounts = accounts;
        this.cards = cards;
        this.dayparts = Collections.unmodifiableMap(new LinkedHashMap<>(dayparts));
        this.programming = Collections.unmodifiableMap(new LinkedHashMap<>(programming));
        this.separation = Collections.unmodifiableMap(new LinkedHashMap<>(separation));
    }

    public Cluster cluster() {
        return cluster;
    }

    public AccountBook accounts() {
        return accounts;
    }

    public RateCardBook cards() {
        return cards;
    }

    public Map<String, DaypartGrid> dayparts() {
        return dayparts;
    }

    public Map<String, ClockGrid> programming() {
        return programming;
    }

    public Map<String, SeparationBook> separation() {
        return separation;
    }

    /** The rule book for one station on one date. */
    public RuleBook book(String call, LocalDate on) {
        Station station = cluster.station(call);
        if (station.isSatellite()) {
            throw new RuleBookError(
                    call + " carries " + station.carries().orElseThrow() + ", ask for that station's book instead");
        }
        if (!dayparts.containsKey(call)) {
            throw new RuleBookError(call + " has no daypart grid filed");
        }
        if (!programming.containsKey(call)) {
            throw new RuleBookError(call + " has no programming grid filed");
        }
        return new RuleBook(
                station,
                on,
                dayparts.get(call),
                programming.get(call),
                separation.getOrDefault(call, new SeparationBook()),
                cards.inForce(call, on));
    }

    /** Every station that gets a log built on {@code on}. */
    public List<RuleBook> books(LocalDate on) {
        return cluster.logged().stream()
                .map(station -> book(station.call(), on))
                .toList();
    }

    /** Where a spot bought on {@code call} actually has to be scheduled. */
    public String loggingCall(String call) {
        return cluster.loggingStation(call).call();
    }
}
