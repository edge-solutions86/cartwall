package io.github.rkaushikdev.cartwall.persistence;

import io.github.rkaushikdev.cartwall.Settings;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.function.Consumer;

/**
 * The suite's database: one H2 in memory, migrated once for the whole run and emptied before each
 * test that asks for it.
 *
 * <p>The original built and threw away an in-memory SQLite database per test. Building a Hibernate
 * factory per test costs more than the tests do, so this keeps one database and one factory and
 * gives every test the same thing the original did: the schema, and nothing in it, with the
 * generated keys counting from one.
 */
public final class TestDatabase {

    public static final String URL = "jdbc:h2:mem:cartwall-tests;DB_CLOSE_DELAY=-1";

    private static Database shared;

    private TestDatabase() {}

    /** The shared database, emptied. */
    public static synchronized Database fresh() {
        if (shared == null) {
            shared = Database.open(Settings.defaults().withDatabase(URL));
            shared.migrate();
        }
        empty(shared);
        return shared;
    }

    /** Open a unit of work on a fresh database, let {@code work} fill it, and commit. */
    public static Database seeded(Consumer<UnitOfWork> work) {
        Database database = fresh();
        try (UnitOfWork uow = database.unitOfWork()) {
            work.accept(uow);
            uow.commit();
        }
        return database;
    }

    private static void empty(Database database) {
        List<String> tables = database.tableNames();
        try (Connection connection = database.dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET REFERENTIAL_INTEGRITY FALSE");
            for (String table : tables) {
                statement.execute("TRUNCATE TABLE " + table + " RESTART IDENTITY");
            }
            statement.execute("SET REFERENTIAL_INTEGRITY TRUE");
        } catch (SQLException trouble) {
            throw new IllegalStateException("could not empty the test database", trouble);
        }
    }
}
