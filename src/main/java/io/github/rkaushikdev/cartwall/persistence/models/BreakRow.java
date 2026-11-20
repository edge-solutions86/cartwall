package io.github.rkaushikdev.cartwall.persistence.models;

import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.domain.BreakKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** One stopset on a stored clock: position and length in tenths, the kind as its short name. */
@Entity
@Table(
        name = "clock_break",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_clock_break_clock_id_number",
                        columnNames = {"clock_id", "number"}))
public class BreakRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clock_id", nullable = false, foreignKey = @ForeignKey(name = "fk_clock_break_clock_id_clock"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ClockRow clock;

    @Column(name = "number", nullable = false)
    private int number;

    @Column(name = "kind", length = 32, nullable = false)
    private BreakKind kind;

    @Column(name = "position", nullable = false)
    private Duration position;

    @Column(name = "length", nullable = false)
    private Duration length;

    @Column(name = "units", nullable = false)
    private int units;

    @Column(name = "paid", nullable = false)
    private boolean paid = true;

    protected BreakRow() {}

    public BreakRow(int number, BreakKind kind, Duration position, Duration length, int units, boolean paid) {
        this.number = number;
        this.kind = kind;
        this.position = position;
        this.length = length;
        this.units = units;
        this.paid = paid;
    }

    /** A break paid orders may go in. */
    public BreakRow(int number, BreakKind kind, Duration position, Duration length, int units) {
        this(number, kind, position, length, units, true);
    }

    public Integer getId() {
        return id;
    }

    public ClockRow getClock() {
        return clock;
    }

    void setClock(ClockRow clock) {
        this.clock = clock;
    }

    public int getNumber() {
        return number;
    }

    public BreakKind getKind() {
        return kind;
    }

    public Duration getPosition() {
        return position;
    }

    public Duration getLength() {
        return length;
    }

    public int getUnits() {
        return units;
    }

    public boolean isPaid() {
        return paid;
    }

    @Override
    public String toString() {
        return "<BreakRow " + (clock == null ? null : clock.getId()) + "/" + number + ">";
    }
}
