package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;

import io.github.rkaushikdev.cartwall.domain.RotationKind;
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

/**
 * One cart in a line's rotation, with its weight and its place in the rotation as written.
 *
 * <p>The rotation has its own table because a line has several carts and they change on their
 * own: killing one cut mid flight rewrites the rotation and touches nothing else on the order.
 */
@Entity
@Table(
        name = "line_rotation",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_line_rotation_line_id_cart",
                        columnNames = {"line_id", "cart"}))
public class RotationRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "line_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_line_rotation_line_id_sales_line"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private LineRow line;

    /** Where this cart sits in the rotation as written. Sequential rotations run in this order. */
    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "cart", length = CODE_LENGTH, nullable = false)
    private String cart;

    @Column(name = "weight", nullable = false)
    private int weight = 1;

    @Column(name = "kind", length = 32, nullable = false)
    private RotationKind kind = RotationKind.EVEN;

    /** Declares the foreign key on {@code cart}. Written through the cart column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "cart",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_line_rotation_cart_cut"))
    private CutRow cut;

    protected RotationRow() {}

    public RotationRow(int position, String cart, int weight, RotationKind kind) {
        this.position = position;
        this.cart = cart;
        this.weight = weight;
        this.kind = kind;
    }

    public Integer getId() {
        return id;
    }

    public LineRow getLine() {
        return line;
    }

    void setLine(LineRow line) {
        this.line = line;
    }

    public int getPosition() {
        return position;
    }

    public String getCart() {
        return cart;
    }

    public int getWeight() {
        return weight;
    }

    public RotationKind getKind() {
        return kind;
    }

    CutRow getCut() {
        return cut;
    }

    @Override
    public String toString() {
        return "<RotationRow " + (line == null ? null : line.getId()) + " " + cart + ">";
    }
}
