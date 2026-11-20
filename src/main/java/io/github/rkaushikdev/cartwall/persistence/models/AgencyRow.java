package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;
import static io.github.rkaushikdev.cartwall.persistence.Columns.NAME_LENGTH;

import io.github.rkaushikdev.cartwall.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** One buying agency, with its commission kept as an exact decimal. */
@Entity
@Table(name = "agency")
public class AgencyRow {

    @Id
    @Column(name = "code", length = CODE_LENGTH, nullable = false)
    private String code;

    @Column(name = "name", length = NAME_LENGTH, nullable = false)
    private String name;

    @Column(name = "rate", precision = 5, scale = 4, nullable = false)
    private BigDecimal rate = Money.AGENCY_RATE;

    @Column(name = "advertiser_liable", nullable = false)
    private boolean advertiserLiable;

    protected AgencyRow() {}

    public AgencyRow(String code, String name, BigDecimal rate, boolean advertiserLiable) {
        this.code = code;
        this.name = name;
        this.rate = rate;
        this.advertiserLiable = advertiserLiable;
    }

    /** An agency at the standard fifteen percent. */
    public AgencyRow(String code, String name) {
        this(code, name, Money.AGENCY_RATE, false);
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }

    public boolean isAdvertiserLiable() {
        return advertiserLiable;
    }

    public void setAdvertiserLiable(boolean advertiserLiable) {
        this.advertiserLiable = advertiserLiable;
    }

    @Override
    public String toString() {
        return "<AgencyRow " + code + ">";
    }
}
