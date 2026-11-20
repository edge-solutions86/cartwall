package io.github.rkaushikdev.cartwall.persistence.models;

import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.services.Offer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * One makegood proposed against one debt.
 *
 * <p>Offers point at the debt by id. There is no natural key for an offer worth having: the same
 * daypart on the same day can be offered twice if the first was refused.
 */
@Entity
@Table(
        name = "makegood_offer",
        indexes = {
            @Index(name = "ix_makegood_offer_debt_id", columnList = "debt_id"),
            @Index(name = "ix_makegood_offer_day", columnList = "day")
        })
public class OfferRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "debt_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_makegood_offer_debt_id_makegood_debt"))
    private DebtRow debt;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "daypart", length = DebtRow.LABEL_LENGTH, nullable = false)
    private String daypart;

    @Column(name = "rate", nullable = false)
    private Money rate;

    @Column(name = "state", length = 32, nullable = false)
    private Offer state = Offer.OPEN;

    /** Worth less than what it replaces. Set once, when the offer is made. */
    @Column(name = "short", nullable = false)
    private boolean isShort;

    protected OfferRow() {}

    /** An offer against {@code debt}. Enter it on the debt with {@link DebtRow#addOffer}. */
    public OfferRow(DebtRow debt, LocalDate day, String daypart, Money rate, Offer state, boolean isShort) {
        this.debt = debt;
        this.day = day;
        this.daypart = daypart;
        this.rate = rate;
        this.state = state;
        this.isShort = isShort;
    }

    public Integer getId() {
        return id;
    }

    public DebtRow getDebt() {
        return debt;
    }

    public LocalDate getDay() {
        return day;
    }

    public String getDaypart() {
        return daypart;
    }

    public Money getRate() {
        return rate;
    }

    public Offer getState() {
        return state;
    }

    public void setState(Offer state) {
        this.state = state;
    }

    public boolean isShort() {
        return isShort;
    }

    @Override
    public String toString() {
        return "<OfferRow " + daypart + " on " + day + " (" + state.value() + ")>";
    }
}
