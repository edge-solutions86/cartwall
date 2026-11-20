package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;
import static io.github.rkaushikdev.cartwall.persistence.Columns.NAME_LENGTH;

import io.github.rkaushikdev.cartwall.domain.Credit;
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
import java.util.ArrayList;
import java.util.List;

/**
 * One advertiser, with its conflict codes and its copy library.
 *
 * <p>Closing an account takes its codes and its cuts with it. The codes are stored one per row,
 * with the first segment split out, because "who else is on AUTO" is the question the log builder
 * asks most.
 */
@Entity
@Table(name = "advertiser")
public class AdvertiserRow {

    @Id
    @Column(name = "code", length = CODE_LENGTH, nullable = false)
    private String code;

    @Column(name = "name", length = NAME_LENGTH, nullable = false)
    private String name;

    @Column(name = "agency", length = CODE_LENGTH)
    private String agency;

    @Column(name = "parent", length = CODE_LENGTH)
    private String parent;

    @Column(name = "credit", length = 32, nullable = false)
    private Credit credit = Credit.OPEN;

    @OneToMany(mappedBy = "account", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("path")
    private List<CategoryRow> categories = new ArrayList<>();

    @OneToMany(mappedBy = "account", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("cart")
    private List<CutRow> cuts = new ArrayList<>();

    /** Declares the foreign key on {@code agency}. Written through the code column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "agency",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_advertiser_agency_agency"))
    private AgencyRow buyer;

    /** Declares the foreign key on {@code parent}. Written through the code column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "parent",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_advertiser_parent_advertiser"))
    private AdvertiserRow chain;

    protected AdvertiserRow() {}

    public AdvertiserRow(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getAgency() {
        return agency;
    }

    public void setAgency(String agency) {
        this.agency = agency;
    }

    public String getParent() {
        return parent;
    }

    public void setParent(String parent) {
        this.parent = parent;
    }

    public Credit getCredit() {
        return credit;
    }

    public void setCredit(Credit credit) {
        this.credit = credit;
    }

    /** The conflict codes, in path order once read back. */
    public List<CategoryRow> getCategories() {
        return categories;
    }

    /** Replace the conflict codes, each one filed against this advertiser. */
    public void setCategories(List<CategoryRow> categories) {
        this.categories.clear();
        for (CategoryRow row : categories) {
            row.fileAgainst(this);
            this.categories.add(row);
        }
    }

    /** The copy, in cart order once read back. */
    public List<CutRow> getCuts() {
        return cuts;
    }

    /** Replace the copy, each cut filed against this advertiser. */
    public void setCuts(List<CutRow> cuts) {
        this.cuts.clear();
        for (CutRow row : cuts) {
            row.fileAgainst(this);
            this.cuts.add(row);
        }
    }

    AgencyRow getBuyer() {
        return buyer;
    }

    AdvertiserRow getChain() {
        return chain;
    }

    @Override
    public String toString() {
        return "<AdvertiserRow " + code + ">";
    }
}
