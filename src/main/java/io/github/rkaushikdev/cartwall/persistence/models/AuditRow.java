package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;
import static io.github.rkaushikdev.cartwall.persistence.Columns.NAME_LENGTH;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * One thing somebody did, and enough to find it again: the audit trail, append only.
 *
 * <p>There are no foreign keys out of this table, on purpose. An audit entry has to outlive what
 * it describes: an order that is deleted, a log that is revised away, an advertiser closed and
 * purged. Nothing updates a row here and nothing deletes one.
 */
@Entity
@Table(
        name = "audit",
        indexes = {
            @Index(name = "ix_audit_subject", columnList = "kind, reference"),
            @Index(name = "ix_audit_at", columnList = "at")
        })
public class AuditRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "at", nullable = false)
    private LocalDateTime at;

    /** The event name, dotted, matching the log line: {@code order.confirmed}. */
    @Column(name = "action", length = NAME_LENGTH, nullable = false)
    private String action;

    /** Who: a person's initials, or the name of the job that ran unattended. */
    @Column(name = "actor", length = NAME_LENGTH, nullable = false)
    private String actor;

    /** What kind of thing it was about: {@code order}, {@code log}, {@code invoice}. */
    @Column(name = "kind", length = CODE_LENGTH, nullable = false)
    private String kind;

    /** Its identifier, as text, with no foreign key. */
    @Column(name = "reference", length = 64, nullable = false)
    private String reference;

    /** Key equals value, the same shape as a log line. */
    @Lob
    @Column(name = "detail", nullable = false)
    private String detail = "";

    protected AuditRow() {}

    public AuditRow(LocalDateTime at, String action, String actor, String kind, String reference, String detail) {
        this.at = at;
        this.action = action;
        this.actor = actor;
        this.kind = kind;
        this.reference = reference;
        this.detail = detail;
    }

    public Integer getId() {
        return id;
    }

    public LocalDateTime getAt() {
        return at;
    }

    public String getAction() {
        return action;
    }

    public String getActor() {
        return actor;
    }

    public String getKind() {
        return kind;
    }

    public String getReference() {
        return reference;
    }

    public String getDetail() {
        return detail;
    }

    @Override
    public String toString() {
        return "<AuditRow " + action + " " + kind + ":" + reference + ">";
    }
}
