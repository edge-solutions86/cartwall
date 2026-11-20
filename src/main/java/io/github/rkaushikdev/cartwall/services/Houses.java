package io.github.rkaushikdev.cartwall.services;

import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.LookupFailure;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.Cluster;
import io.github.rkaushikdev.cartwall.domain.DaypartGrid;
import io.github.rkaushikdev.cartwall.domain.House;
import io.github.rkaushikdev.cartwall.domain.SeparationBook;
import io.github.rkaushikdev.cartwall.domain.Station;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rebuilding the house out of what is on file, for whoever is asking: the service and the command
 * line both need it before they can do anything, and it lives here so they cannot disagree about
 * which grid is in force.
 *
 * <p>It is rebuilt per request rather than cached. A cached house is one rate card behind on the
 * day somebody files a new one, and nobody notices until an invoice is wrong; reading six
 * stations' tables is a handful of small queries. The grid in force is the one with the latest
 * effective date not after the day asked about.
 */
public final class Houses {

    private Houses() {}

    /** The newest grid for {@code call} on file, whatever its effective date. */
    public static ClockGrid gridInForce(UnitOfWork uow, String call) {
        return gridInForce(uow, call, Optional.empty());
    }

    /** The newest grid for {@code call} that had taken effect by {@code on}. */
    public static ClockGrid gridInForce(UnitOfWork uow, String call, Optional<LocalDate> on) {
        String query = "select a.effective from AssignmentRow a where a.station = :call"
                + (on.isPresent() ? " and a.effective <= :on" : "")
                + " order by a.effective desc";
        var select = uow.entityManager().createQuery(query, LocalDate.class).setParameter("call", call);
        on.ifPresent(day -> select.setParameter("on", day));
        LocalDate effective = select.setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElseThrow(
                        () -> new LookupFailure(call + " has no programming grid on file", Details.of("call", call)));
        return uow.stations().grid(call, effective);
    }

    /**
     * The separation rules one station runs. Every station runs the house book at the moment; when
     * a station wants its own, this is the one place that has to change.
     */
    public static SeparationBook separationFor(UnitOfWork uow, String call) {
        return SeparationBook.houseRules();
    }

    /** Everything the cluster has filed, as of {@code on}. */
    public static House houseFrom(UnitOfWork uow, Optional<LocalDate> on) {
        Cluster cluster = uow.stations().cluster();
        Map<String, DaypartGrid> dayparts = new LinkedHashMap<>();
        Map<String, ClockGrid> programming = new LinkedHashMap<>();
        Map<String, SeparationBook> separation = new LinkedHashMap<>();
        List<String> calls = cluster.logged().stream().map(Station::call).toList();
        // One table at a time across the cluster, so the first thing missing is the first thing
        // reported.
        calls.forEach(call -> dayparts.put(call, uow.stations().dayparts(call)));
        calls.forEach(call -> programming.put(call, gridInForce(uow, call, on)));
        calls.forEach(call -> separation.put(call, separationFor(uow, call)));
        return new House(cluster, uow.accounts().book(), uow.cards().book(), dayparts, programming, separation);
    }

    /** The grid a spot bought on {@code station} is actually scheduled against. */
    public static ClockGrid gridFor(UnitOfWork uow, String station, Optional<LocalDate> on) {
        Cluster cluster = uow.stations().cluster();
        return gridInForce(uow, cluster.loggingStation(station).call(), on);
    }
}
