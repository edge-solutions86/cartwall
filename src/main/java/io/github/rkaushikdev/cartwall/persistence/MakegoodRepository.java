package io.github.rkaushikdev.cartwall.persistence;

import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.persistence.models.DebtRow;
import io.github.rkaushikdev.cartwall.persistence.models.OfferRow;
import io.github.rkaushikdev.cartwall.services.Debt;
import io.github.rkaushikdev.cartwall.services.Ledger;
import io.github.rkaushikdev.cartwall.services.Makegood;
import io.github.rkaushikdev.cartwall.services.MakegoodError;
import io.github.rkaushikdev.cartwall.services.NotOwed;
import io.github.rkaushikdev.cartwall.services.Offer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The makegood ledger on disk: one debt at a time, one offer at a time.
 *
 * <p>Raising a debt already on file returns the row that is there, so re-posting a night does not
 * double what a client is owed. {@link #raiseAll} numbers identical debts as it walks, so a line
 * short four spots stays four, and gathering the same span again changes nothing.
 */
public final class MakegoodRepository {

    private final EntityManager entityManager;

    public MakegoodRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** The entity manager this repository shares with the rest of its unit of work. */
    public EntityManager entityManager() {
        return entityManager;
    }

    public static Debt debtFrom(DebtRow row) {
        return new Debt(
                row.getOrderNumber(),
                row.getLine(),
                row.getAdvertiser(),
                row.getStation(),
                row.getDaypart(),
                row.getGrade(),
                row.getLength(),
                row.getRate(),
                row.getDay(),
                row.getCause());
    }

    public static Makegood offerFrom(OfferRow row) {
        return new Makegood(
                debtFrom(row.getDebt()), row.getDay(), row.getDaypart(), row.getRate(), row.getState(), row.isShort());
    }

    /** Put the first of a debt on file. Raising one that is already there is a no-op. */
    public DebtRow raiseDebt(Debt debt) {
        return raiseDebt(debt, 1);
    }

    /**
     * Put a debt on file. Raising one that is already there is a no-op.
     *
     * @param sequence tells identical debts apart; prefer {@link #raiseAll} to numbering by hand
     */
    public DebtRow raiseDebt(Debt debt, int sequence) {
        Optional<DebtRow> found = find(debt, sequence);
        if (found.isPresent()) {
            return found.get();
        }
        DebtRow row = new DebtRow(
                debt.order(),
                debt.line(),
                debt.advertiser(),
                debt.station(),
                debt.daypart(),
                debt.grade(),
                debt.length(),
                debt.rate(),
                debt.day(),
                debt.cause(),
                sequence);
        entityManager.persist(row);
        entityManager.flush();
        return row;
    }

    /**
     * The nightly run's entry point. Answers how many were new.
     *
     * <p>Identical debts are numbered in the order they arrive, so a line owed four spots gets four
     * rows rather than one.
     */
    public int raiseAll(List<Debt> debts) {
        long before = count();
        Map<String, Integer> seen = new HashMap<>();
        for (Debt debt : debts) {
            String key = debt.order() + "|" + debt.line() + "|" + debt.day() + "|"
                    + debt.cause().value();
            raiseDebt(debt, seen.merge(key, 1, Integer::sum));
        }
        return (int) (count() - before);
    }

    /** Offer one spot back against the first spot owed on a debt. */
    public OfferRow propose(Debt debt, LocalDate day, String daypart, Money rate) {
        return propose(debt, day, daypart, rate, 1);
    }

    /** Offer one spot back against one of the spots owed on a debt. */
    public OfferRow propose(Debt debt, LocalDate day, String daypart, Money rate, int sequence) {
        DebtRow found = find(debt, sequence)
                .orElseThrow(() -> new NotOwed(
                        "nothing is owed on " + debt.order() + "-" + debt.line() + " for " + debt.day(),
                        Details.of("order", debt.order(), "line", debt.line())));
        if (found.getOffers().stream().anyMatch(row -> !row.getState().isSettled())) {
            throw new MakegoodError(
                    debt.order() + "-" + debt.line() + " already has an offer open for " + debt.day(),
                    Details.of("order", debt.order(), "line", debt.line()));
        }
        Makegood made = Makegood.offer(debt, day, daypart, rate);
        OfferRow row = new OfferRow(found, made.day(), made.daypart(), made.rate(), made.state(), made.isShort());
        found.addOffer(row);
        entityManager.persist(row);
        entityManager.flush();
        return row;
    }

    /** Record the client's answer against one offer. */
    public OfferRow settle(int offerId, Offer state) {
        OfferRow row = entityManager.find(OfferRow.class, offerId);
        if (row == null) {
            throw new NotOwed("there is no offer " + offerId, Details.of("offer", offerId));
        }
        if (row.getState().isSettled()) {
            throw new MakegoodError(
                    "that offer was already " + row.getState().value(),
                    Details.of("state", row.getState().value()));
        }
        if (state == Offer.OPEN) {
            throw new MakegoodError("an offer cannot be settled as open");
        }
        row.setState(state);
        entityManager.flush();
        return row;
    }

    /** Withdraw every open offer whose air date has gone by. */
    public List<OfferRow> expireBefore(LocalDate day) {
        List<OfferRow> rows = entityManager
                .createQuery(
                        "select o from OfferRow o where o.state = :open and o.day < :day order by o.day, o.id",
                        OfferRow.class)
                .setParameter("open", Offer.OPEN)
                .setParameter("day", day)
                .getResultList();
        rows.forEach(row -> row.setState(Offer.EXPIRED));
        entityManager.flush();
        return rows;
    }

    /** How many debts are on file. */
    public long count() {
        return entityManager
                .createQuery("select count(d) from DebtRow d", Long.class)
                .getSingleResult();
    }

    /** The whole ledger. */
    public Ledger ledger() {
        return ledger(Optional.empty(), Optional.empty());
    }

    /** The ledger of one order, one station, both, or neither. */
    public Ledger ledger(Optional<String> order, Optional<String> station) {
        StringBuilder where = new StringBuilder("select d from DebtRow d where 1 = 1");
        order.ifPresent(number -> where.append(" and d.orderNumber = :order"));
        station.ifPresent(call -> where.append(" and d.station = :station"));
        where.append(" order by d.day, d.orderNumber, d.line, d.id");
        TypedQuery<DebtRow> query = entityManager.createQuery(where.toString(), DebtRow.class);
        order.ifPresent(number -> query.setParameter("order", number));
        station.ifPresent(call -> query.setParameter("station", call));
        Ledger built = new Ledger();
        for (DebtRow row : query.getResultList()) {
            built.owe(
                    debtFrom(row),
                    row.getOffers().stream()
                            .sorted(Comparator.comparing(OfferRow::getId))
                            .map(MakegoodRepository::offerFrom)
                            .toList());
        }
        return built;
    }

    /** Offers nobody has answered yet, soonest air date first. */
    public List<OfferRow> openOffers() {
        return openOffers(Optional.empty());
    }

    /** Offers nobody has answered yet on one order, or on every order. */
    public List<OfferRow> openOffers(Optional<String> order) {
        String where = "select o from OfferRow o join o.debt d where o.state = :open"
                + order.map(number -> " and d.orderNumber = :order").orElse("")
                + " order by o.day, o.id";
        TypedQuery<OfferRow> query =
                entityManager.createQuery(where, OfferRow.class).setParameter("open", Offer.OPEN);
        order.ifPresent(number -> query.setParameter("order", number));
        return query.getResultList();
    }

    /** Debts raised in a span, whether or not anything was offered back. */
    public List<DebtRow> owedBetween(LocalDate first, LocalDate last) {
        return owedBetween(first, last, Optional.empty());
    }

    /** Debts raised in a span on one station, or on every station. */
    public List<DebtRow> owedBetween(LocalDate first, LocalDate last, Optional<String> station) {
        String where = "select d from DebtRow d where d.day >= :first and d.day <= :last"
                + station.map(call -> " and d.station = :station").orElse("")
                + " order by d.day, d.orderNumber, d.line";
        TypedQuery<DebtRow> query = entityManager
                .createQuery(where, DebtRow.class)
                .setParameter("first", first)
                .setParameter("last", last);
        station.ifPresent(call -> query.setParameter("station", call));
        return query.getResultList();
    }

    private Optional<DebtRow> find(Debt debt, int sequence) {
        return entityManager
                .createQuery(
                        "select d from DebtRow d where d.orderNumber = :order and d.line = :line and d.day = :day"
                                + " and d.cause = :cause and d.sequence = :sequence",
                        DebtRow.class)
                .setParameter("order", debt.order())
                .setParameter("line", debt.line())
                .setParameter("day", debt.day())
                .setParameter("cause", debt.cause())
                .setParameter("sequence", sequence)
                .getResultStream()
                .findFirst();
    }
}
