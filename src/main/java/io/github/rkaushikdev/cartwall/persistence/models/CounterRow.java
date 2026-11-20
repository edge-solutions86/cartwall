package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * One document number series for one year. The only row anybody locks.
 *
 * <p>It lives with the other tables rather than beside the numbering service that draws from it,
 * so there is one answer to what the schema is.
 */
@Entity
@Table(name = "counter")
public class CounterRow {

    /** Document numbers open here so a four digit number always reads as one. */
    public static final int FIRST_NUMBER = 1000;

    @EmbeddedId
    private Key key;

    @Column(name = "last", nullable = false)
    private int last = FIRST_NUMBER;

    protected CounterRow() {}

    public CounterRow(String series, int year) {
        this.key = new Key(series, year);
    }

    public String getSeries() {
        return key.series;
    }

    public int getYear() {
        return key.year;
    }

    public int getLast() {
        return last;
    }

    public void setLast(int last) {
        this.last = last;
    }

    @Override
    public String toString() {
        return "<CounterRow " + key.series + " " + key.year + " at " + last + ">";
    }

    /**
     * The counter's key: a series and a year, in that order.
     *
     * @see CounterRow#find
     */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "series", length = CODE_LENGTH, nullable = false)
        private String series;

        @Column(name = "year", nullable = false)
        private int year;

        protected Key() {}

        public Key(String series, int year) {
            this.series = series;
            this.year = year;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && year == key.year && Objects.equals(series, key.series);
        }

        @Override
        public int hashCode() {
            return Objects.hash(series, year);
        }
    }

    /** The key {@code em.find(CounterRow.class, ...)} takes for one series in one year. */
    public static Key find(String series, int year) {
        return new Key(series, year);
    }
}
