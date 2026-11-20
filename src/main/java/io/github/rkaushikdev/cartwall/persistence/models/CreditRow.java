package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;
import static io.github.rkaushikdev.cartwall.persistence.Columns.NAME_LENGTH;

import io.github.rkaushikdev.cartwall.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * One credit memo: how an invoice gets corrected without being edited.
 *
 * <p>A credit points at the invoice it corrects and carries a reason from a fixed list. Money on
 * a credit is stored positive and applied negative, which is the way the office reads it on paper.
 */
@Entity
@Table(name = "credit_memo", indexes = @Index(name = "ix_credit_memo_raised", columnList = "raised"))
public class CreditRow {

    @Id
    @Column(name = "number", length = 24, nullable = false)
    private String number;

    /** The invoice this corrects. A credit never floats free of one. */
    @Column(name = "invoice_number", length = 24, nullable = false)
    private String invoiceNumber;

    @Column(name = "advertiser", length = CODE_LENGTH, nullable = false)
    private String advertiser;

    @Column(name = "payer", length = CODE_LENGTH, nullable = false)
    private String payer;

    @Column(name = "raised", nullable = false)
    private LocalDate raised;

    @Column(name = "reason", length = 32, nullable = false)
    private Reason reason;

    @Column(name = "note", length = NAME_LENGTH, nullable = false)
    private String note = "";

    @Column(name = "gross", nullable = false)
    private Money gross;

    @Column(name = "commission", nullable = false)
    private Money commission;

    @Column(name = "net", nullable = false)
    private Money net;

    @Column(name = "sent", nullable = false)
    private boolean sent;

    @Column(name = "spots", nullable = false)
    private int spots;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "invoice_number",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_credit_memo_invoice_number_invoice"))
    private InvoiceRow invoice;

    /** Declares the foreign key on {@code advertiser}. Written through the code column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "advertiser",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_credit_memo_advertiser_advertiser"))
    private AdvertiserRow account;

    protected CreditRow() {}

    public CreditRow(
            String number,
            String invoiceNumber,
            String advertiser,
            String payer,
            LocalDate raised,
            Reason reason,
            Money gross,
            Money commission,
            Money net) {
        this.number = number;
        this.invoiceNumber = invoiceNumber;
        this.advertiser = advertiser;
        this.payer = payer;
        this.raised = raised;
        this.reason = reason;
        this.gross = gross;
        this.commission = commission;
        this.net = net;
    }

    public String getNumber() {
        return number;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public InvoiceRow getInvoice() {
        return invoice;
    }

    public String getAdvertiser() {
        return advertiser;
    }

    public String getPayer() {
        return payer;
    }

    public LocalDate getRaised() {
        return raised;
    }

    public Reason getReason() {
        return reason;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Money getGross() {
        return gross;
    }

    public Money getCommission() {
        return commission;
    }

    public Money getNet() {
        return net;
    }

    public boolean isSent() {
        return sent;
    }

    public void setSent(boolean sent) {
        this.sent = sent;
    }

    public int getSpots() {
        return spots;
    }

    public void setSpots(int spots) {
        this.spots = spots;
    }

    AdvertiserRow getAccount() {
        return account;
    }

    @Override
    public String toString() {
        return "<CreditRow " + number + " against " + invoiceNumber + ">";
    }
}
