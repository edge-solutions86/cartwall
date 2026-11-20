package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;

import io.github.rkaushikdev.cartwall.AirTime;
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
import java.time.LocalDate;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** One spot on an invoice, copied as it aired, with the time off the as-run. */
@Entity
@Table(name = "invoice_detail")
public class InvoiceDetailRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "invoice_number", length = 24, nullable = false)
    private String invoiceNumber;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    /** When it really ran, off the as-run. Not when it was logged. */
    @Column(name = "at", nullable = false)
    private AirTime at;

    @Column(name = "line", nullable = false)
    private int line;

    @Column(name = "length", nullable = false)
    private SpotLength length;

    @Column(name = "rate", nullable = false)
    private Money rate;

    @Column(name = "cart", length = CODE_LENGTH, nullable = false)
    private String cart = "";

    @Column(name = "moved", nullable = false)
    private boolean moved;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "invoice_number",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_invoice_detail_invoice_number_invoice"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private InvoiceRow invoice;

    protected InvoiceDetailRow() {}

    public InvoiceDetailRow(
            LocalDate day, AirTime at, int line, SpotLength length, Money rate, String cart, boolean moved) {
        this.day = day;
        this.at = at;
        this.line = line;
        this.length = length;
        this.rate = rate;
        this.cart = cart;
        this.moved = moved;
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

    public LocalDate getDay() {
        return day;
    }

    public AirTime getAt() {
        return at;
    }

    public int getLine() {
        return line;
    }

    public SpotLength getLength() {
        return length;
    }

    public Money getRate() {
        return rate;
    }

    public String getCart() {
        return cart;
    }

    public boolean isMoved() {
        return moved;
    }

    @Override
    public String toString() {
        return "<InvoiceDetailRow " + invoiceNumber + " " + day + " " + at + ">";
    }
}
