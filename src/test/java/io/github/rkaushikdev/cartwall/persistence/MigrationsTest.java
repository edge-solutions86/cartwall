package io.github.rkaushikdev.cartwall.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.github.rkaushikdev.cartwall.Settings;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

/**
 * The migrations and the mapping have to agree, and only a test can say so.
 *
 * <p>A migration written by hand drifts from the entities the first time somebody adds a column
 * and forgets. The check is not "does it run", it is "does what it builds match what Hibernate
 * expects", so these build one schema by running the migrations and one straight from the mapping
 * with Hibernate's own schema export, and compare them table by table and column by column.
 */
class MigrationsTest {

    private static HikariDataSource migratedPool;
    private static HikariDataSource builtPool;
    private static LocalContainerEntityManagerFactoryBean builder;
    private static Set<String> declared;

    @BeforeAll
    static void buildBothSchemas() {
        Database migrated = Database.open(Settings.defaults().withDatabase("jdbc:h2:mem:migrated;DB_CLOSE_DELAY=-1"));
        migrated.migrate();
        migratedPool = (HikariDataSource) migrated.dataSource();

        builtPool = Database.pool("jdbc:h2:mem:built;DB_CLOSE_DELAY=-1");
        builder = Database.entityManagerFactoryBean(builtPool, false, "create");
        builder.afterPropertiesSet();
        EntityManagerFactory factory = builder.getObject();
        declared = new TreeSet<>();
        for (EntityType<?> entity : factory.getMetamodel().getEntities()) {
            declared.add(entity.getJavaType().getAnnotation(Table.class).name());
        }
    }

    @AfterAll
    static void closeBoth() {
        builder.destroy();
        builtPool.close();
        migratedPool.close();
    }

    static Stream<String> tables() {
        return declared.stream();
    }

    private static Set<String> tablesIn(DataSource source) {
        return new TreeSet<>(rows(
                source,
                "select table_name from information_schema.tables where table_schema = 'PUBLIC'"
                        + " and table_type = 'BASE TABLE' and table_name <> 'flyway_schema_history'",
                null));
    }

    private static List<String> rows(DataSource source, String sql, String table) {
        List<String> found = new ArrayList<>();
        try (Connection connection = source.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if (table != null) {
                statement.setString(1, table.toUpperCase(Locale.ROOT));
            }
            try (ResultSet rows = statement.executeQuery()) {
                int width = rows.getMetaData().getColumnCount();
                while (rows.next()) {
                    StringBuilder row = new StringBuilder();
                    for (int column = 1; column <= width; column++) {
                        row.append(column == 1 ? "" : " | ").append(rows.getString(column));
                    }
                    found.add(row.toString().toLowerCase(Locale.ROOT));
                }
            }
        } catch (SQLException trouble) {
            throw new IllegalStateException(sql, trouble);
        }
        return found;
    }

    private static Set<String> both(String sql, String table, DataSource source) {
        return new TreeSet<>(rows(source, sql, table));
    }

    private static final String COLUMNS = "select column_name, is_nullable from information_schema.columns"
            + " where table_schema = 'PUBLIC' and table_name = ?";

    private static final String TYPES = "select column_name, data_type, character_maximum_length,"
            + " numeric_precision, numeric_scale from information_schema.columns"
            + " where table_schema = 'PUBLIC' and table_name = ?";

    private static final String PRIMARY_KEY = "select k.column_name, k.ordinal_position"
            + " from information_schema.table_constraints c join information_schema.key_column_usage k"
            + " on c.constraint_name = k.constraint_name and c.table_name = k.table_name"
            + " where c.table_schema = 'PUBLIC' and c.constraint_type = 'PRIMARY KEY' and c.table_name = ?";

    private static final String UNIQUES = "select c.constraint_name,"
            + " listagg(k.column_name, ',') within group (order by k.ordinal_position)"
            + " from information_schema.table_constraints c join information_schema.key_column_usage k"
            + " on c.constraint_name = k.constraint_name and c.table_name = k.table_name"
            + " where c.table_schema = 'PUBLIC' and c.constraint_type = 'UNIQUE' and c.table_name = ?"
            + " group by c.constraint_name";

    private static final String FOREIGN_KEYS = "select k.column_name, u.table_name, u.column_name"
            + " from information_schema.referential_constraints r"
            + " join information_schema.key_column_usage k on r.constraint_name = k.constraint_name"
            + " join information_schema.key_column_usage u on r.unique_constraint_name = u.constraint_name"
            + " and k.position_in_unique_constraint = u.ordinal_position"
            + " where k.table_schema = 'PUBLIC' and k.table_name = ?";

    private static final String INDEXES = "select i.index_name, i.index_type_name,"
            + " listagg(c.column_name, ',') within group (order by c.ordinal_position)"
            + " from information_schema.indexes i join information_schema.index_columns c"
            + " on i.index_name = c.index_name and i.table_name = c.table_name"
            + " where i.table_schema = 'PUBLIC' and i.table_name = ? and i.index_type_name = 'INDEX'"
            + " and i.index_name not like 'FK%' group by i.index_name, i.index_type_name";

    @Test
    void theMigrationBuildsEveryTableTheModelsDeclare() {
        assertThat(tablesIn(migratedPool)).isEqualTo(declared);
    }

    @Test
    void theMigrationBuildsTheSameTablesAsTheMapping() {
        assertThat(tablesIn(migratedPool)).isEqualTo(tablesIn(builtPool));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tables")
    void everyColumnMatchesTheModel(String name) {
        assertThat(both(COLUMNS, name, migratedPool)).as(name).isEqualTo(both(COLUMNS, name, builtPool));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tables")
    void everyColumnTypeMatchesTheModel(String name) {
        assertThat(both(TYPES, name, migratedPool)).as(name).isEqualTo(both(TYPES, name, builtPool));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tables")
    void everyPrimaryKeyMatchesTheModel(String name) {
        assertThat(both(PRIMARY_KEY, name, migratedPool)).as(name).isEqualTo(both(PRIMARY_KEY, name, builtPool));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tables")
    void everyUniqueConstraintMatchesTheModel(String name) {
        assertThat(both(UNIQUES, name, migratedPool)).as(name).isEqualTo(both(UNIQUES, name, builtPool));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tables")
    void everyForeignKeyMatchesTheModel(String name) {
        assertThat(both(FOREIGN_KEYS, name, migratedPool)).as(name).isEqualTo(both(FOREIGN_KEYS, name, builtPool));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tables")
    void everyIndexMatchesTheModel(String name) {
        assertThat(both(INDEXES, name, migratedPool)).as(name).isEqualTo(both(INDEXES, name, builtPool));
    }

    /**
     * The comparisons above pass when both sides read nothing, so this one shows that each of them
     * reads something, and that a change planted on one side is seen.
     */
    @Test
    void theComparisonsReadTheSchemaAndSeeADifference() throws SQLException {
        for (String kind : List.of(COLUMNS, TYPES, PRIMARY_KEY, UNIQUES, FOREIGN_KEYS, INDEXES)) {
            for (DataSource side : List.of(migratedPool, builtPool)) {
                assertThat(declared.stream()
                                .mapToInt(name -> both(kind, name, side).size())
                                .sum())
                        .as(kind)
                        .isPositive();
            }
        }
        try (Database planted =
                Database.open(Settings.defaults().withDatabase("jdbc:h2:mem:planted;DB_CLOSE_DELAY=-1"))) {
            planted.migrate();
            DataSource side = planted.dataSource();
            try (Connection connection = side.getConnection();
                    PreparedStatement column =
                            connection.prepareStatement("ALTER TABLE advertiser ADD COLUMN planted INTEGER");
                    PreparedStatement index =
                            connection.prepareStatement("CREATE INDEX ix_advertiser_planted ON advertiser (name)");
                    PreparedStatement unique = connection.prepareStatement(
                            "ALTER TABLE agency ADD CONSTRAINT uq_agency_planted UNIQUE (name)");
                    PreparedStatement key = connection.prepareStatement(
                            "ALTER TABLE cut DROP CONSTRAINT fk_cut_advertiser_advertiser")) {
                column.execute();
                index.execute();
                unique.execute();
                key.execute();
            }
            assertThat(both(COLUMNS, "advertiser", side)).isNotEqualTo(both(COLUMNS, "advertiser", builtPool));
            assertThat(both(TYPES, "advertiser", side)).isNotEqualTo(both(TYPES, "advertiser", builtPool));
            assertThat(both(INDEXES, "advertiser", side)).isNotEqualTo(both(INDEXES, "advertiser", builtPool));
            assertThat(both(UNIQUES, "agency", side)).isNotEqualTo(both(UNIQUES, "agency", builtPool));
            assertThat(both(FOREIGN_KEYS, "cut", side)).isNotEqualTo(both(FOREIGN_KEYS, "cut", builtPool));
            assertThat(both(COLUMNS, "station", side)).isEqualTo(both(COLUMNS, "station", builtPool));
        }
    }

    @Test
    void theMigrationReverses(@TempDir Path tmp) {
        try (Database database =
                Database.open(Settings.defaults().withDatabase("jdbc:h2:file:" + tmp.resolve("reversed")))) {
            database.migrate();
            assertThat(database.tableNames()).isNotEmpty();
            database.drop();
            assertThat(database.tableNames()).isEmpty();
        }
    }

    @Test
    void upgradingTwiceIsANoOp(@TempDir Path tmp) {
        try (Database database =
                Database.open(Settings.defaults().withDatabase("jdbc:h2:file:" + tmp.resolve("twice")))) {
            database.migrate();
            List<String> before = database.tableNames();
            database.migrate();
            assertThat(database.tableNames()).isEqualTo(before);
        }
    }

    @Test
    void theCounterTableIsInTheMigrationEvenThoughItLivesInServices() {
        // It is declared with the other rows rather than beside the service that draws from it,
        // so that one scan of the mapping always builds it.
        assertThat(declared).contains("counter");
    }
}
