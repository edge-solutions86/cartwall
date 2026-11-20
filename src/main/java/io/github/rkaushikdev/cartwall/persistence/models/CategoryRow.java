package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;

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
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** One conflict code on one advertiser: the dotted path, and its first segment for the index. */
@Entity
@Table(
        name = "advertiser_category",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_advertiser_category_advertiser_path",
                        columnNames = {"advertiser", "path"}),
        indexes = @Index(name = "ix_advertiser_category_root", columnList = "root"))
public class CategoryRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "advertiser", length = CODE_LENGTH, nullable = false)
    private String advertiser;

    /** The dotted code, stored whole: {@code AUTO.DOMESTIC.FORD}. */
    @Column(name = "path", length = 64, nullable = false)
    private String path;

    /** The first segment, so the common "who else is on AUTO" query is indexed. */
    @Column(name = "root", length = 32, nullable = false)
    private String root;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "advertiser",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_advertiser_category_advertiser_advertiser"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AdvertiserRow account;

    protected CategoryRow() {}

    public CategoryRow(String path, String root) {
        this.path = path;
        this.root = root;
    }

    void fileAgainst(AdvertiserRow owner) {
        this.advertiser = owner.getCode();
        this.account = owner;
    }

    public Integer getId() {
        return id;
    }

    public String getAdvertiser() {
        return advertiser;
    }

    public String getPath() {
        return path;
    }

    public String getRoot() {
        return root;
    }

    @Override
    public String toString() {
        return "<CategoryRow " + advertiser + " " + path + ">";
    }
}
