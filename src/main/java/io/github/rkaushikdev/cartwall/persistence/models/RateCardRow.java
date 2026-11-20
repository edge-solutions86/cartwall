package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** One station's card, effective from a date, with a quoted thirty per daypart and class. */
@Entity
@Table(
        name = "rate_card",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_rate_card_station_effective",
                        columnNames = {"station", "effective"}))
public class RateCardRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "code", length = CODE_LENGTH, nullable = false)
    private String code;

    @Column(name = "station", length = CODE_LENGTH, nullable = false)
    private String station;

    @Column(name = "effective", nullable = false)
    private LocalDate effective;

    @OneToMany(mappedBy = "card", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<QuoteRow> quotes = new ArrayList<>();

    /** Declares the foreign key on {@code station}. Written through the code column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "station",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_rate_card_station_station"))
    private StationRow signal;

    protected RateCardRow() {}

    public RateCardRow(String code, String station, LocalDate effective) {
        this.code = code;
        this.station = station;
        this.effective = effective;
    }

    public Integer getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getStation() {
        return station;
    }

    public LocalDate getEffective() {
        return effective;
    }

    /** The quotes, in the order they were filed. */
    public List<QuoteRow> getQuotes() {
        return quotes;
    }

    /** Replace the quotes, each one pointed back at this card. */
    public void setQuotes(List<QuoteRow> quotes) {
        this.quotes.clear();
        quotes.forEach(this::addQuote);
    }

    public void addQuote(QuoteRow row) {
        row.setCard(this);
        quotes.add(row);
    }

    StationRow getSignal() {
        return signal;
    }

    @Override
    public String toString() {
        return "<RateCardRow " + code + ">";
    }
}
