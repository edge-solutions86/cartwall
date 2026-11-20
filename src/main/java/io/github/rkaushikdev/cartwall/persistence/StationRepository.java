package io.github.rkaushikdev.cartwall.persistence;

import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.LookupFailure;
import io.github.rkaushikdev.cartwall.domain.Clock;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.Cluster;
import io.github.rkaushikdev.cartwall.domain.Daypart;
import io.github.rkaushikdev.cartwall.domain.DaypartGrid;
import io.github.rkaushikdev.cartwall.domain.Station;
import io.github.rkaushikdev.cartwall.persistence.models.AssignmentRow;
import io.github.rkaushikdev.cartwall.persistence.models.ClockRow;
import io.github.rkaushikdev.cartwall.persistence.models.DaypartRow;
import io.github.rkaushikdev.cartwall.persistence.models.StationRow;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;

/**
 * Signals, dayparts and programming grids: domain objects in, domain objects out, rows in the
 * middle.
 *
 * <p>Two habits every repository keeps. Reads are ordered, always, because an unordered read is a
 * test that passes on one database and fails on another eighteen months later. Writes replace
 * rather than patch, because patching means working out a diff at every call site.
 */
public final class StationRepository {

    private final EntityManager entityManager;

    public StationRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** The entity manager this repository shares with the rest of its unit of work. */
    public EntityManager entityManager() {
        return entityManager;
    }

    /** File every signal in the cluster, replacing what is on file under the same call. */
    public void saveCluster(Cluster cluster) {
        for (StationRow row : Mappers.clusterRows(cluster)) {
            entityManager.merge(row);
        }
    }

    /** The cluster, logged signals first and then the ones that simulcast them. */
    public Cluster cluster() {
        List<StationRow> rows = entityManager
                .createQuery(
                        "select s from StationRow s order by case when s.carries is null then 0 else 1 end, s.call",
                        StationRow.class)
                .getResultList();
        return Mappers.clusterFrom(rows);
    }

    public Station station(String call) {
        StationRow row = entityManager.find(StationRow.class, call);
        if (row == null) {
            throw new LookupFailure(call + " is not on file", Details.of("call", call));
        }
        return Mappers.stationFrom(row);
    }

    /** Replace a station's daypart grid. */
    public void saveDayparts(String call, DaypartGrid grid) {
        entityManager
                .createQuery("delete from DaypartRow d where d.station = :call")
                .setParameter("call", call)
                .executeUpdate();
        for (Daypart daypart : grid.dayparts()) {
            entityManager.persist(Mappers.daypartRow(daypart, call));
        }
    }

    /** A station's daypart grid, in code order. */
    public DaypartGrid dayparts(String call) {
        List<DaypartRow> rows = entityManager
                .createQuery("select d from DaypartRow d where d.station = :call order by d.code", DaypartRow.class)
                .setParameter("call", call)
                .getResultList();
        return Mappers.daypartGridFrom(rows);
    }

    /** Replace a station's grid for its effective date: the clocks it files and the stack. */
    public void saveGrid(ClockGrid grid) {
        entityManager
                .createQuery("delete from AssignmentRow a where a.station = :call and a.effective = :effective")
                .setParameter("call", grid.station())
                .setParameter("effective", grid.effective())
                .executeUpdate();
        for (Clock clock : grid.clocks().values()) {
            List<ClockRow> existing = entityManager
                    .createQuery(
                            "select c from ClockRow c where c.station = :call and c.code = :code"
                                    + " and c.effective = :effective",
                            ClockRow.class)
                    .setParameter("call", grid.station())
                    .setParameter("code", clock.code())
                    .setParameter("effective", grid.effective())
                    .getResultList();
            if (!existing.isEmpty()) {
                entityManager.remove(existing.get(0));
                entityManager.flush();
            }
            entityManager.persist(Mappers.clockRow(clock, grid.station(), grid.effective()));
        }
        for (AssignmentRow row : Mappers.assignmentRows(grid)) {
            entityManager.persist(row);
        }
    }

    /** A station's grid as it was filed for one effective date. */
    public ClockGrid grid(String call, LocalDate effective) {
        List<ClockRow> clocks = entityManager
                .createQuery(
                        "select c from ClockRow c where c.station = :call and c.effective = :effective order by c.code",
                        ClockRow.class)
                .setParameter("call", call)
                .setParameter("effective", effective)
                .getResultList();
        List<AssignmentRow> assignments = entityManager
                .createQuery(
                        "select a from AssignmentRow a where a.station = :call and a.effective = :effective"
                                + " order by a.ordinal",
                        AssignmentRow.class)
                .setParameter("call", call)
                .setParameter("effective", effective)
                .getResultList();
        if (clocks.isEmpty() && assignments.isEmpty()) {
            throw new LookupFailure(call + " has no grid effective " + effective, Details.of("call", call));
        }
        return Mappers.clockGridFrom(call, effective, clocks, assignments);
    }
}
