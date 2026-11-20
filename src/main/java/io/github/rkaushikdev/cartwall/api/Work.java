package io.github.rkaushikdev.cartwall.api;

import io.github.rkaushikdev.cartwall.persistence.Database;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One unit of work per request: committed if the handler returns, rolled back if it throws.
 *
 * <p>Handlers do not commit. A handler that commits halfway and then fails leaves half a change
 * behind, and the point of the transaction is that this cannot happen. Reads go through the same
 * door: committing a transaction that only read costs nothing, and one code path rather than two is
 * how a write never ends up on the read one.
 *
 * <p>The commit happens before the handler's answer is written, which is why this is a call around
 * the handler's body and not an interceptor: Spring writes a response body before an interceptor's
 * {@code postHandle} runs, so a commit there that failed would follow a 200.
 */
public final class Work {

    private final Database database;

    public Work(Database database) {
        this.database = database;
    }

    /** Run {@code handler} in a fresh unit of work and answer what it answers. */
    public <T> T apply(Function<UnitOfWork, T> handler) {
        try (UnitOfWork uow = database.unitOfWork()) {
            T answer = handler.apply(uow);
            uow.commit();
            return answer;
        }
    }

    /** Run {@code handler} in a fresh unit of work, for a route that answers with no body. */
    public void run(Consumer<UnitOfWork> handler) {
        apply(uow -> {
            handler.accept(uow);
            return null;
        });
    }
}
