package io.github.rkaushikdev.cartwall.services;

import static io.github.rkaushikdev.cartwall.HouseLog.Field.field;

import io.github.rkaushikdev.cartwall.HouseLog;
import io.github.rkaushikdev.cartwall.HouseLog.Field;
import io.github.rkaushikdev.cartwall.persistence.models.AuditRow;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Writing the audit trail, and reading it back when somebody asks.
 *
 * <p>The writes are deliberately narrow: {@link #record} takes who, what and about what, and the
 * kind and the reference are required and neither may be blank, so nobody writes a row nobody can
 * find later. There are only three questions anybody asks of the trail: what happened to this
 * document, what did this person do, and what happened on this day.
 *
 * <p>Writing to the trail never fails a caller's work. {@link #tryRecord} logs a record that
 * cannot be written and swallows it, because refusing to confirm an order because the audit table
 * is full is worse than losing an audit row. The trade is made here rather than at every call site.
 */
public final class Audit {

    /** The actor written when the nightly log build did it. */
    public static final String NIGHTLY = "log builder";

    /** The actor written when the invoice run did it. */
    public static final String BILLING = "invoice run";

    /** The actor written when as-run posting did it. */
    public static final String RECONCILER = "as run posting";

    private static final String SPAN = "select a from AuditRow a where a.at >= :first and a.at <= :last";

    private Audit() {}

    /** Append one entry, stamped now. Refuses an entry nobody could find again. */
    public static AuditRow record(
            EntityManager entityManager, String action, String actor, String kind, Object reference, Field... detail) {
        return record(entityManager, action, actor, kind, reference, LocalDateTime.now(), detail);
    }

    /** Append one entry at a given moment. Refuses an entry nobody could find again. */
    public static AuditRow record(
            EntityManager entityManager,
            String action,
            String actor,
            String kind,
            Object reference,
            LocalDateTime at,
            Field... detail) {
        if (action == null || action.isEmpty()) {
            throw new AuditError("an audit entry needs an action");
        }
        if (actor == null || actor.isEmpty()) {
            throw new AuditError(action + " was done by nobody");
        }
        String written = reference == null ? "" : String.valueOf(reference);
        if (kind == null || kind.isEmpty() || written.isEmpty()) {
            throw new AuditError(action + " is about nothing in particular");
        }
        AuditRow row = new AuditRow(at, action, actor, kind, written, HouseLog.pairs(detail));
        entityManager.persist(row);
        return row;
    }

    /** Append, and swallow a failure rather than losing the caller's work. */
    public static Optional<AuditRow> tryRecord(
            EntityManager entityManager, String action, String actor, String kind, Object reference, Field... detail) {
        try {
            return Optional.of(record(entityManager, action, actor, kind, reference, detail));
        } catch (RuntimeException trouble) {
            HouseLog.failed(
                    "audit.lost",
                    field("action", action),
                    field("kind", kind),
                    field("reference", reference),
                    field("why", trouble.getMessage()));
            return Optional.empty();
        }
    }

    /** Everything that happened to one document, oldest first. */
    public static List<Entry> about(EntityManager entityManager, String kind, Object reference) {
        return entries(entityManager
                .createQuery(
                        "select a from AuditRow a where a.kind = :kind and a.reference = :reference"
                                + " order by a.at, a.id",
                        AuditRow.class)
                .setParameter("kind", kind)
                .setParameter("reference", String.valueOf(reference))
                .getResultList());
    }

    /** Everything one person did in a span, oldest first. */
    public static List<Entry> byActor(EntityManager entityManager, String actor, LocalDate first, LocalDate last) {
        if (last.isBefore(first)) {
            throw new AuditError("the span runs backwards");
        }
        return entries(entityManager
                .createQuery(SPAN + " and a.actor = :actor order by a.at, a.id", AuditRow.class)
                .setParameter("first", first.atStartOfDay())
                .setParameter("last", last.atTime(LocalTime.MAX))
                .setParameter("actor", actor)
                .getResultList());
    }

    /** Everything, oldest first, for somebody with a complaint and a date. */
    public static List<Entry> onDay(EntityManager entityManager, LocalDate day) {
        return entries(entityManager
                .createQuery(SPAN + " order by a.at, a.id", AuditRow.class)
                .setParameter("first", day.atStartOfDay())
                .setParameter("last", day.atTime(LocalTime.MAX))
                .getResultList());
    }

    /** How many entries the trail holds. */
    public static long count(EntityManager entityManager) {
        return entityManager
                .createQuery("select count(a) from AuditRow a", Long.class)
                .getSingleResult();
    }

    public static Entry entryFrom(AuditRow row) {
        return new Entry(
                row.getAt(), row.getAction(), row.getActor(), row.getKind(), row.getReference(), row.getDetail());
    }

    private static List<Entry> entries(List<AuditRow> rows) {
        return rows.stream().map(Audit::entryFrom).toList();
    }

    /** One row of the trail, as a caller sees it. */
    public record Entry(LocalDateTime at, String action, String actor, String kind, String reference, String detail) {

        private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

        @Override
        public String toString() {
            String tail = detail.isEmpty() ? "" : " " + detail;
            return at.format(STAMP) + " " + actor + " " + action + " " + kind + ":" + reference + tail;
        }
    }
}
