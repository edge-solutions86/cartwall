package io.github.rkaushikdev.cartwall.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

/**
 * One persistence context, one transaction, and every repository hanging off it.
 *
 * <p>Closing without committing rolls back. That is the default on purpose: traffic runs a lot of
 * jobs that read, decide, and then decide not to write, and the failure mode of an implicit commit
 * is a half built log that looks finished.
 *
 * <pre>{@code
 * try (UnitOfWork uow = database.unitOfWork()) {
 *     Order order = uow.orders().get("25-1044");
 *     uow.orders().save(order.confirmed());
 *     uow.commit();
 * }
 * }</pre>
 *
 * <p>The repositories share the entity manager, so anything one of them writes is visible to the
 * next without a commit. That is the whole reason they are gathered here. A commit begins the next
 * transaction straight away, so work can carry on in the same unit.
 */
public final class UnitOfWork implements AutoCloseable {

    private final EntityManager entityManager;
    private final StationRepository stations;
    private final AccountRepository accounts;
    private final RateCardRepository cards;
    private final OrderRepository orders;
    private final MakegoodRepository makegoods;
    private boolean closed;
    private boolean committed;

    /** A unit of work over an entity manager somebody opened, its transaction begun here. */
    public UnitOfWork(EntityManager entityManager) {
        this.entityManager = entityManager;
        this.stations = new StationRepository(entityManager);
        this.accounts = new AccountRepository(entityManager);
        this.cards = new RateCardRepository(entityManager);
        this.orders = new OrderRepository(entityManager);
        this.makegoods = new MakegoodRepository(entityManager);
        entityManager.getTransaction().begin();
    }

    /** The entity manager every repository of this unit shares. */
    public EntityManager entityManager() {
        return entityManager;
    }

    public StationRepository stations() {
        return stations;
    }

    public AccountRepository accounts() {
        return accounts;
    }

    public RateCardRepository cards() {
        return cards;
    }

    public OrderRepository orders() {
        return orders;
    }

    public MakegoodRepository makegoods() {
        return makegoods;
    }

    public boolean committed() {
        return committed;
    }

    public void flush() {
        checkOpen();
        entityManager.flush();
    }

    /** Make the work so far stick, and begin the next transaction. */
    public void commit() {
        checkOpen();
        entityManager.getTransaction().commit();
        committed = true;
        entityManager.getTransaction().begin();
    }

    /** Undo the work since the last commit, forget what was loaded, and begin again. */
    public void rollback() {
        checkOpen();
        EntityTransaction transaction = entityManager.getTransaction();
        if (transaction.isActive()) {
            transaction.rollback();
        }
        entityManager.clear();
        committed = false;
        transaction.begin();
    }

    /** Roll back whatever was not committed and close. Safe to call twice. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            EntityTransaction transaction = entityManager.getTransaction();
            if (transaction.isActive()) {
                transaction.rollback();
            }
        } finally {
            entityManager.close();
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new UnitOfWorkError("this unit of work has already been closed");
        }
    }
}
