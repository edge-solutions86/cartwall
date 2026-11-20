package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;
import static io.github.rkaushikdev.cartwall.persistence.Columns.NAME_LENGTH;

import io.github.rkaushikdev.cartwall.domain.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One order on file, with every line on it.
 *
 * <p>The flight lives on the line rather than in a table of its own: it has no identity apart
 * from the line and nobody queries flights.
 */
@Entity
@Table(name = "sales_order")
public class OrderRow {

    @Id
    @Column(name = "number", length = 24, nullable = false)
    private String number;

    @Column(name = "advertiser", length = CODE_LENGTH, nullable = false)
    private String advertiser;

    @Column(name = "agency", length = CODE_LENGTH)
    private String agency;

    @Column(name = "written", nullable = false)
    private LocalDate written;

    @Column(name = "salesperson", length = CODE_LENGTH, nullable = false)
    private String salesperson;

    @Column(name = "campaign", length = NAME_LENGTH, nullable = false)
    private String campaign = "";

    @Column(name = "status", length = 32, nullable = false)
    private OrderStatus status = OrderStatus.PENDING;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("number")
    private List<LineRow> lines = new ArrayList<>();

    /** Declares the foreign key on {@code advertiser}. Written through the code column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "advertiser",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_sales_order_advertiser_advertiser"))
    private AdvertiserRow account;

    /** Declares the foreign key on {@code agency}. Written through the code column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "agency",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_sales_order_agency_agency"))
    private AgencyRow buyer;

    protected OrderRow() {}

    public OrderRow(String number, String advertiser, LocalDate written, String salesperson) {
        this.number = number;
        this.advertiser = advertiser;
        this.written = written;
        this.salesperson = salesperson;
    }

    public String getNumber() {
        return number;
    }

    public String getAdvertiser() {
        return advertiser;
    }

    public String getAgency() {
        return agency;
    }

    public void setAgency(String agency) {
        this.agency = agency;
    }

    public LocalDate getWritten() {
        return written;
    }

    public String getSalesperson() {
        return salesperson;
    }

    public String getCampaign() {
        return campaign;
    }

    public void setCampaign(String campaign) {
        this.campaign = campaign;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    /** The lines, in number order once read back. */
    public List<LineRow> getLines() {
        return lines;
    }

    /** Replace the lines, each one pointed back at this order. */
    public void setLines(List<LineRow> lines) {
        this.lines.clear();
        for (LineRow row : lines) {
            row.placeOn(this);
            this.lines.add(row);
        }
    }

    AdvertiserRow getAccount() {
        return account;
    }

    AgencyRow getBuyer() {
        return buyer;
    }

    @Override
    public String toString() {
        return "<OrderRow " + number + ">";
    }
}
