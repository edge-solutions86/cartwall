package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;

import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** One piece of audio on file: its sold length in seconds and its running time in tenths. */
@Entity
@Table(name = "cut")
public class CutRow {

    @Id
    @Column(name = "cart", length = CODE_LENGTH, nullable = false)
    private String cart;

    @Column(name = "isci", length = 24, nullable = false)
    private String isci;

    @Column(name = "advertiser", length = CODE_LENGTH, nullable = false)
    private String advertiser;

    @Column(name = "length", nullable = false)
    private SpotLength length;

    /** What it actually runs, in tenths. Not the same number as the length. */
    @Column(name = "actual", nullable = false)
    private Duration actual;

    @Column(name = "first_air", nullable = false)
    private LocalDate firstAir;

    @Column(name = "last_air")
    private LocalDate lastAir;

    @Column(name = "killed", nullable = false)
    private boolean killed;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "advertiser",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_cut_advertiser_advertiser"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AdvertiserRow account;

    protected CutRow() {}

    /** A cut to file through its advertiser's copy library. */
    public CutRow(String cart, String isci, SpotLength length, Duration actual, LocalDate firstAir) {
        this.cart = cart;
        this.isci = isci;
        this.length = length;
        this.actual = actual;
        this.firstAir = firstAir;
    }

    /** A cut filed against an advertiser by code. */
    public CutRow(
            String cart,
            String isci,
            String advertiser,
            SpotLength length,
            Duration actual,
            LocalDate firstAir,
            LocalDate lastAir,
            boolean killed) {
        this(cart, isci, length, actual, firstAir);
        this.advertiser = advertiser;
        this.lastAir = lastAir;
        this.killed = killed;
    }

    void fileAgainst(AdvertiserRow owner) {
        this.advertiser = owner.getCode();
        this.account = owner;
    }

    public String getCart() {
        return cart;
    }

    public String getIsci() {
        return isci;
    }

    public String getAdvertiser() {
        return advertiser;
    }

    public SpotLength getLength() {
        return length;
    }

    public Duration getActual() {
        return actual;
    }

    public LocalDate getFirstAir() {
        return firstAir;
    }

    public LocalDate getLastAir() {
        return lastAir;
    }

    public void setLastAir(LocalDate lastAir) {
        this.lastAir = lastAir;
    }

    public boolean isKilled() {
        return killed;
    }

    public void setKilled(boolean killed) {
        this.killed = killed;
    }

    @Override
    public String toString() {
        return "<CutRow " + cart + ">";
    }
}
