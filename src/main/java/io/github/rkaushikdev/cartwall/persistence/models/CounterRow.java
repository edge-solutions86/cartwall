package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
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
@IdClass(CounterRow.Key.class)
public class CounterRow {

    /** Document numbers open here so a four digit number always reads as one. */
    public static final int FIRST_NUMBER = 1000;

    @Id
    @Column(name = "series", length = CODE_LENGTH, nullable = false)
    private String series;

    @Id
    @Column(name = "year", nullable = false)
    private int year;

    @Column(name = "last", nullable = false)
    private int last = FIRST_NUMBER;

    protected CounterRow() {}

    public CounterRow(String series, int year) {
        this.series = series;
        this.year = year;
    }

    public String getSeries() {
        return series;
    }

    public int getYear() {
        return year;
    }

    public int getLast() {
        return last;
    }

    public void setLast(int last) {
        this.last = last;
    }

    @Override
    public String toString() {
        return "<CounterRow " + series + " " + year + " at " + last + ">";
    }

    /** The counter's key: a series and a year. */
    public static class Key implements Serializable {

        private String series;
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
}
