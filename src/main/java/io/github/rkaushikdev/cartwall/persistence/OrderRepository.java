package io.github.rkaushikdev.cartwall.persistence;

import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.LookupFailure;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.persistence.models.OrderRow;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Orders and the lines on them. Saving an order replaces the lines that are gone. */
public final class OrderRepository {

    private final EntityManager entityManager;

    public OrderRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** The entity manager this repository shares with the rest of its unit of work. */
    public EntityManager entityManager() {
        return entityManager;
    }

    /** Write an order whole, replacing what is on file under its number. */
    public void save(Order order) {
        save(order, Map.of());
    }

    /**
     * Write an order whole, replacing what is on file under its number.
     *
     * @param cards the card each line's rate came off, by line number
     */
    public void save(Order order, Map<Integer, String> cards) {
        OrderRow existing = entityManager.find(OrderRow.class, order.number());
        if (existing != null) {
            entityManager.remove(existing);
            entityManager.flush();
        }
        entityManager.persist(Mappers.orderRow(order, cards));
    }

    public Order get(String number) {
        return row(number).map(Mappers::orderFrom).orElseThrow(() -> notOnFile(number));
    }

    /** The stored row, for the services that read what was written beside the domain object. */
    public Optional<OrderRow> row(String number) {
        return Optional.ofNullable(entityManager.find(OrderRow.class, number));
    }

    public boolean exists(String number) {
        return entityManager.find(OrderRow.class, number) != null;
    }

    /** Every order for one advertiser, by number. */
    public List<Order> forAdvertiser(String advertiser) {
        return entityManager
                .createQuery(
                        "select o from OrderRow o where o.advertiser = :advertiser order by o.number", OrderRow.class)
                .setParameter("advertiser", advertiser)
                .getResultList()
                .stream()
                .map(Mappers::orderFrom)
                .toList();
    }

    /** Every order with a line whose flight covers {@code day}, by number. */
    public List<Order> runningOn(LocalDate day) {
        List<String> numbers = entityManager
                .createQuery(
                        "select distinct l.orderNumber from LineRow l where l.firstAir <= :day and l.lastAir >= :day",
                        String.class)
                .setParameter("day", day)
                .getResultList();
        return numbers.stream()
                .map(this::get)
                .sorted(Comparator.comparing(Order::number))
                .toList();
    }

    public void delete(String number) {
        OrderRow row = entityManager.find(OrderRow.class, number);
        if (row == null) {
            throw notOnFile(number);
        }
        entityManager.remove(row);
    }

    private static LookupFailure notOnFile(String number) {
        return new LookupFailure("order " + number + " is not on file", Details.of("order", number));
    }
}
