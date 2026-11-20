package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;

import io.github.rkaushikdev.cartwall.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One invoice as it was raised: a statement of what we said we were owed on a date.
 *
 * <p>The money is printed, not derived, and the detail is copied rather than joined. Everything
 * an invoice was built from can be revised afterwards, and the invoice itself cannot.
 */
@Entity
@Table(
        name = "invoice",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_invoice_order_number_year_month",
                        columnNames = {"order_number", "year", "month"}),
        indexes = @Index(name = "ix_invoice_raised", columnList = "raised"))
public class InvoiceRow {

    @Id
    @Column(name = "number", length = 24, nullable = false)
    private String number;

    @Column(name = "order_number", length = 24, nullable = false)
    private String orderNumber;

    @Column(name = "advertiser", length = CODE_LENGTH, nullable = false)
    private String advertiser;

    /** Who the invoice goes to. The agency when there is one. */
    @Column(name = "payer", length = CODE_LENGTH, nullable = false)
    private String payer;

    /** The broadcast year, which is not the calendar year. */
    @Column(name = "year", nullable = false)
    private int year;

    /** The broadcast month, which is not the calendar month. */
    @Column(name = "month", nullable = false)
    private int month;

    @Column(name = "raised", nullable = false)
    private LocalDate raised;

    @Column(name = "spots", nullable = false)
    private int spots;

    @Column(name = "gross", nullable = false)
    private Money gross;

    @Column(name = "commission_rate", precision = 5, scale = 4, nullable = false)
    private BigDecimal commissionRate = BigDecimal.ZERO;

    @Column(name = "commission", nullable = false)
    private Money commission;

    @Column(name = "net", nullable = false)
    private Money net;

    @Column(name = "sent", nullable = false)
    private boolean sent;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("day, at")
    private List<InvoiceDetailRow> details = new ArrayList<>();

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("line")
    private List<InvoiceSummaryRow> summaries = new ArrayList<>();

    /** Declares the foreign key on {@code order_number}. Written through the number column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "order_number",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_invoice_order_number_sales_order"))
    private OrderRow order;

    /** Declares the foreign key on {@code advertiser}. Written through the code column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "advertiser",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_invoice_advertiser_advertiser"))
    private AdvertiserRow account;

    protected InvoiceRow() {}

    public InvoiceRow(
            String number,
            String orderNumber,
            String advertiser,
            String payer,
            int year,
            int month,
            LocalDate raised,
            Money gross,
            Money commission,
            Money net) {
        this.number = number;
        this.orderNumber = orderNumber;
        this.advertiser = advertiser;
        this.payer = payer;
        this.year = year;
        this.month = month;
        this.raised = raised;
        this.gross = gross;
        this.commission = commission;
        this.net = net;
    }

    public String getNumber() {
        return number;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public String getAdvertiser() {
        return advertiser;
    }

    public String getPayer() {
        return payer;
    }

    public int getYear() {
        return year;
    }

    public int getMonth() {
        return month;
    }

    public LocalDate getRaised() {
        return raised;
    }

    public int getSpots() {
        return spots;
    }

    public void setSpots(int spots) {
        this.spots = spots;
    }

    public Money getGross() {
        return gross;
    }

    public BigDecimal getCommissionRate() {
        return commissionRate;
    }

    public void setCommissionRate(BigDecimal commissionRate) {
        this.commissionRate = commissionRate;
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

    /** The detail, by day and time once read back. */
    public List<InvoiceDetailRow> getDetails() {
        return details;
    }

    /** Replace the detail, each row pointed back at this invoice. */
    public void setDetails(List<InvoiceDetailRow> details) {
        this.details.clear();
        for (InvoiceDetailRow row : details) {
            row.billOn(this);
            this.details.add(row);
        }
    }

    /** The summary, by line once read back. */
    public List<InvoiceSummaryRow> getSummaries() {
        return summaries;
    }

    /** Replace the summary, each row pointed back at this invoice. */
    public void setSummaries(List<InvoiceSummaryRow> summaries) {
        this.summaries.clear();
        for (InvoiceSummaryRow row : summaries) {
            row.billOn(this);
            this.summaries.add(row);
        }
    }

    OrderRow getOrder() {
        return order;
    }

    AdvertiserRow getAccount() {
        return account;
    }

    @Override
    public String toString() {
        return "<InvoiceRow %s %04d-%02d>".formatted(number, year, month);
    }
}
