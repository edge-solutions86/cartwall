package io.github.rkaushikdev.cartwall.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.rkaushikdev.cartwall.Settings;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/**
 * One database: a connection pool, the schema's migrations, and the entity manager factory units
 * of work are opened from.
 *
 * <p>The command line opens one of these from the settings and nothing else, so a cron job starts
 * without a framework and honours {@code --database} before anything connects. The service builds
 * the same pieces as beans and hands them in.
 *
 * <p>The entity manager factory is built when first asked for, and it validates the mapping
 * against the schema when it is. A database nobody has migrated can still be pinged, migrated and
 * dropped.
 */
public final class Database implements AutoCloseable {

    /** Where the entities and their column converters live. */
    public static final String MAPPED_PACKAGE = "io.github.rkaushikdev.cartwall.persistence";

    /** Where the migrations live, one per revision of the schema. */
    public static final String MIGRATIONS = "classpath:db/migration";

    /**
     * Column names the schema uses that H2 otherwise reads as keywords. They are ordinary names in
     * the domain, so the URL tells H2 so rather than the schema renaming them.
     */
    static final String H2_NON_KEYWORDS = "NON_KEYWORDS=DAY,HOUR,MONTH,YEAR";

    private static final String HISTORY_TABLE = "flyway_schema_history";

    private final String url;
    private final DataSource dataSource;
    private final boolean echoSql;
    private final HikariDataSource ownedPool;
    private EntityManagerFactory factory;
    private LocalContainerEntityManagerFactoryBean ownedFactory;

    private Database(String url, DataSource dataSource, boolean echoSql, HikariDataSource ownedPool) {
        this.url = url;
        this.dataSource = dataSource;
        this.echoSql = echoSql;
        this.ownedPool = ownedPool;
    }

    /** A database over pieces somebody else owns and closes, as the service's beans are. */
    public Database(String url, DataSource dataSource, EntityManagerFactory factory) {
        this(url, dataSource, false, null);
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /** Open the database the settings name. Nothing connects until something is asked. */
    public static Database open(Settings settings) {
        HikariDataSource pool = pool(settings.databaseUrl());
        return new Database(settings.databaseUrl(), pool, settings.echoSql(), pool);
    }

    /** A pool for {@code url}, sized for one process and connecting lazily. */
    public static HikariDataSource pool(String url) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl(url));
        config.setPoolName("cartwall");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setInitializationFailTimeout(-1);
        // A database that will not answer is reported within seconds, not after half a minute.
        config.setConnectionTimeout(3_000);
        return new HikariDataSource(config);
    }

    /** The URL as the driver gets it: H2 is told the schema's column names are not keywords. */
    public static String jdbcUrl(String configured) {
        if (configured.startsWith("jdbc:h2:")
                && !configured.toUpperCase(Locale.ROOT).contains("NON_KEYWORDS")) {
            return configured + ";" + H2_NON_KEYWORDS;
        }
        return configured;
    }

    /**
     * The entity manager factory every part of the process opens units of work from.
     *
     * @param ddl what Hibernate does to the schema at start up: {@code validate} in service, {@code
     *     create} only to build a schema straight from the mapping for comparison
     */
    public static LocalContainerEntityManagerFactoryBean entityManagerFactoryBean(
            DataSource dataSource, boolean echoSql, String ddl) {
        LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
        bean.setDataSource(dataSource);
        bean.setPackagesToScan(MAPPED_PACKAGE);
        bean.setPersistenceUnitName("cartwall");
        bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("hibernate.hbm2ddl.auto", ddl);
        properties.put("hibernate.show_sql", String.valueOf(echoSql));
        properties.put("hibernate.default_batch_fetch_size", "64");
        properties.put("hibernate.jdbc.time_zone", "UTC");
        // Hibernate otherwise lays columns out by type size, composite keys included, and a
        // schema it builds stops matching the order the migrations declare.
        properties.put("hibernate.column_ordering_strategy", "legacy");
        bean.setJpaPropertyMap(properties);
        return bean;
    }

    /** The URL as configured, which is what the command line prints. */
    public String url() {
        return url;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /** The factory, built and validated against the schema the first time it is asked for. */
    public synchronized EntityManagerFactory entityManagerFactory() {
        if (factory == null) {
            ownedFactory = entityManagerFactoryBean(dataSource, echoSql, "validate");
            ownedFactory.afterPropertiesSet();
            factory = ownedFactory.getObject();
        }
        return factory;
    }

    /** A fresh unit of work with its transaction begun. Roll back unless somebody commits. */
    public UnitOfWork unitOfWork() {
        return new UnitOfWork(entityManagerFactory().createEntityManager());
    }

    /** Put the schema in a database that has none, or bring an old one up to date. */
    public void migrate() {
        flyway().migrate();
    }

    /** Take the schema out. Everything in it goes with it. */
    public void drop() {
        closeFactory();
        flyway().clean();
    }

    /** The tables in the database, lower case and sorted, the migrations' own bookkeeping aside. */
    public List<String> tableNames() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            TreeSet<String> names = new TreeSet<>();
            try (ResultSet tables = metadata.getTables(null, null, "%", new String[] {"TABLE"})) {
                while (tables.next()) {
                    String schema = tables.getString("TABLE_SCHEM");
                    if (schema != null && schema.equalsIgnoreCase("INFORMATION_SCHEMA")) {
                        continue;
                    }
                    names.add(tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                }
            }
            names.remove(HISTORY_TABLE);
            return List.copyOf(new ArrayList<>(names));
        } catch (SQLException trouble) {
            throw new DatabaseUnavailable(url, trouble);
        }
    }

    /** Ask the database to answer. Refuses, saying why, when it will not. */
    public void ping() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        } catch (SQLException trouble) {
            throw new DatabaseUnavailable(url, trouble);
        }
    }

    /** Close what this database opened itself. Safe to call twice. */
    @Override
    public synchronized void close() {
        closeFactory();
        if (ownedPool != null) {
            ownedPool.close();
        }
    }

    private synchronized void closeFactory() {
        if (ownedFactory != null) {
            ownedFactory.destroy();
            ownedFactory = null;
            factory = null;
        }
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATIONS)
                .cleanDisabled(false)
                .load();
    }
}
