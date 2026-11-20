package io.github.rkaushikdev.cartwall.persistence;

import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.LookupFailure;
import io.github.rkaushikdev.cartwall.persistence.models.BumpRow;
import io.github.rkaushikdev.cartwall.scheduling.BumpQueue;
import io.github.rkaushikdev.cartwall.scheduling.Bumped;
import io.github.rkaushikdev.cartwall.scheduling.LineKey;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What came off the air, and whether anything has been done about it. */
public final class BumpRepository {

    private final EntityManager entityManager;

    public BumpRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public BumpRow record(String station, Bumped bumped) {
        BumpRow row = new BumpRow(
                station,
                bumped.day(),
                bumped.hour(),
                bumped.number(),
                bumped.placement().order(),
                bumped.placement().line(),
                bumped.placement().advertiser(),
                bumped.placement().length(),
                bumped.placement().grade(),
                bumped.byOrder(),
                bumped.byLine());
        entityManager.persist(row);
        return row;
    }

    public List<BumpRow> recordQueue(String station, BumpQueue queue) {
        List<BumpRow> rows = new ArrayList<>();
        queue.forEach(bumped -> rows.add(record(station, bumped)));
        return List.copyOf(rows);
    }

    /** Bumps nobody has made good yet, oldest first. */
    public List<BumpRow> outstanding() {
        return outstandingWhere("", null);
    }

    /** One order's bumps nobody has made good yet, oldest first. */
    public List<BumpRow> outstanding(String order) {
        return outstandingWhere(" and b.orderNumber = :order", order);
    }

    /** Mark one bump as made good. */
    public BumpRow settle(int rowId) {
        BumpRow row = entityManager.find(BumpRow.class, rowId);
        if (row == null) {
            throw new LookupFailure("bump " + rowId + " is not on file", Details.of("bump", rowId));
        }
        row.setMadeGood(true);
        entityManager.flush();
        return row;
    }

    /** How many spots each line is owed off the bump queue, worst first. */
    public Map<LineKey, Integer> owed() {
        Map<LineKey, Integer> tally = new HashMap<>();
        outstanding().forEach(row -> tally.merge(row.key(), 1, Integer::sum));
        Map<LineKey, Integer> ordered = new LinkedHashMap<>();
        tally.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<LineKey, Integer> entry) -> -entry.getValue())
                        .thenComparing(Map.Entry::getKey))
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }

    /** Drop the bumps for a day that is being rebuilt from scratch. Answers how many went. */
    public int clearDay(String station, LocalDate day) {
        return entityManager
                .createQuery("delete from BumpRow b where b.station = :station and b.day = :day")
                .setParameter("station", station)
                .setParameter("day", day)
                .executeUpdate();
    }

    private List<BumpRow> outstandingWhere(String clause, String order) {
        TypedQuery<BumpRow> query = entityManager.createQuery(
                "select b from BumpRow b where b.madeGood = false" + clause + " order by b.day, b.hour, b.breakNumber",
                BumpRow.class);
        if (order != null) {
            query.setParameter("order", order);
        }
        return query.getResultList();
    }
}
