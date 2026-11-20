package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;

import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
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

/** One order line's month on an invoice, which is what the seller reads. */
@Entity
@Table(
        name = "invoice_summary",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_invoice_summary_invoice_number_line",
                        columnNames = {"invoice_number", "line"}))
public class InvoiceSummaryRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "invoice_number", length = 24, nullable = false)
    private String invoiceNumber;

    @Column(name = "line", nullable = false)
    private int line;

    @Column(name = "station", length = CODE_LENGTH, nullable = false)
    private String station;

    @Column(name = "daypart", length = CODE_LENGTH, nullable = false)
    private String daypart;

    @Column(name = "length", nullable = false)
    private SpotLength length;

    @Column(name = "rate", nullable = false)
    private Money rate;

    @Column(name = "spots", nullable = false)
    private int spots;

    @Column(name = "gross", nullable = false)
    private Money gross;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "invoice_number",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_invoice_summary_invoice_number_invoice"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private InvoiceRow invoice;

    protected InvoiceSummaryRow() {}

    public InvoiceSummaryRow(
            int line, String station, String daypart, SpotLength length, Money rate, int spots, Money gross) {
        this.line = line;
        this.station = station;
        this.daypart = daypart;
        this.length = length;
        this.rate = rate;
        this.spots = spots;
        this.gross = gross;
    }

    void billOn(InvoiceRow owner) {
        this.invoiceNumber = owner.getNumber();
        this.invoice = owner;
    }

    public Integer getId() {
        return id;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public int getLine() {
        return line;
    }

    public String getStation() {
        return station;
    }

    public String getDaypart() {
        return daypart;
    }

    public SpotLength getLength() {
        return length;
    }

    public Money getRate() {
        return rate;
    }

    public int getSpots() {
        return spots;
    }

    public Money getGross() {
        return gross;
    }

    @Override
    public String toString() {
        return "<InvoiceSummaryRow " + invoiceNumber + " line " + line + ">";
    }
}
