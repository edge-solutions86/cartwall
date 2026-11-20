package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;

import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
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

/** One quoted thirty on a card. Every other length comes off it by a factor. */
@Entity
@Table(
        name = "rate_quote",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_rate_quote_card_id_daypart_grade",
                        columnNames = {"card_id", "daypart", "grade"}))
public class QuoteRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id", nullable = false, foreignKey = @ForeignKey(name = "fk_rate_quote_card_id_rate_card"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private RateCardRow card;

    @Column(name = "daypart", length = CODE_LENGTH, nullable = false)
    private String daypart;

    @Column(name = "grade", length = 32, nullable = false)
    private ClassOfTime grade;

    /** What a thirty costs. */
    @Column(name = "thirty", nullable = false)
    private Money thirty;

    protected QuoteRow() {}

    public QuoteRow(String daypart, ClassOfTime grade, Money thirty) {
        this.daypart = daypart;
        this.grade = grade;
        this.thirty = thirty;
    }

    public Integer getId() {
        return id;
    }

    public RateCardRow getCard() {
        return card;
    }

    void setCard(RateCardRow card) {
        this.card = card;
    }

    public String getDaypart() {
        return daypart;
    }

    public ClassOfTime getGrade() {
        return grade;
    }

    public Money getThirty() {
        return thirty;
    }

    @Override
    public String toString() {
        return "<QuoteRow " + (card == null ? null : card.getId()) + " " + daypart + " " + grade + ">";
    }
}
