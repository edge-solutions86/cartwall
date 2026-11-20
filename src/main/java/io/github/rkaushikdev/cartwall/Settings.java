package io.github.rkaushikdev.cartwall;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Everything the process needs to know that is not in the database, read once at start up.
 *
 * <p>Nothing reads the environment again. A process whose behaviour changes because somebody
 * exported a variable while it was running is a process nobody can reason about, and traffic runs
 * unattended overnight. The only genuinely deployment specific setting is the database; the rest
 * have defaults that are right for the house and wrong for nobody.
 *
 * @param databaseUrl the JDBC URL of the database
 * @param market the house, printed on paper and used to pick the cluster
 * @param horizonDays how many days ahead the log builder runs
 * @param echoSql echo SQL; useful once a year and painful the rest of the time
 * @param strictCopy refuse to release a log with any spot missing copy; off only for tests
 */
public record Settings(String databaseUrl, String market, int horizonDays, boolean echoSql, boolean strictCopy) {

    public static final String PREFIX = "CARTWALL_";

    /**
     * A file database beside the working directory. {@code AUTO_SERVER} lets the command line run
     * from cron while the service holds the same file open.
     */
    public static final String DEFAULT_URL = "jdbc:h2:file:./cartwall;AUTO_SERVER=TRUE";

    public static final String DEFAULT_MARKET = "Waterloo Cedar Falls";

    /** How far ahead logs are built. Two weeks is what the automation will hold. */
    public static final int DEFAULT_HORIZON_DAYS = 14;

    public Settings {
        if (databaseUrl == null || databaseUrl.isEmpty()) {
            throw new ConfigError("no database was configured");
        }
        if (horizonDays < 1) {
            throw new ConfigError("a horizon of " + horizonDays + " days is not a horizon");
        }
        if (horizonDays > 120) {
            throw new ConfigError("a horizon of " + horizonDays + " days is further out than any card is filed");
        }
    }

    /** The settings the house runs with when nobody says otherwise. */
    public static Settings defaults() {
        return new Settings(DEFAULT_URL, DEFAULT_MARKET, DEFAULT_HORIZON_DAYS, false, true);
    }

    /** Read from the process environment. */
    public static Settings fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /** Read from {@code environment}, which stands in for the process environment. */
    public static Settings fromEnvironment(Map<String, String> environment) {
        return from(environment::get);
    }

    /**
     * Read through {@code lookup}, which answers a variable name with its value or null.
     *
     * <p>The service passes Spring's environment here, so a test property and an exported
     * variable are read the same way.
     */
    public static Settings from(UnaryOperator<String> lookup) {
        return new Settings(
                Optional.ofNullable(lookup.apply(PREFIX + "DATABASE_URL")).orElse(DEFAULT_URL),
                Optional.ofNullable(lookup.apply(PREFIX + "MARKET")).orElse(DEFAULT_MARKET),
                number(lookup, "HORIZON_DAYS", DEFAULT_HORIZON_DAYS),
                flag(lookup, "ECHO_SQL", false),
                flag(lookup, "STRICT_COPY", true));
    }

    /** Whether the database is H2, embedded in this process or beside it. */
    public boolean isH2() {
        return databaseUrl.startsWith("jdbc:h2:");
    }

    /** The same settings pointed at another database. */
    public Settings withDatabase(String url) {
        return new Settings(url, market, horizonDays, echoSql, strictCopy);
    }

    @Override
    public String toString() {
        return market + " on " + databaseUrl;
    }

    private static boolean flag(UnaryOperator<String> lookup, String name, boolean fallback) {
        String raw = lookup.apply(PREFIX + name);
        if (raw == null) {
            return fallback;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> throw new ConfigError(PREFIX + name + " is '" + raw + "', which is not a yes or a no");
        };
    }

    private static int number(UnaryOperator<String> lookup, String name, int fallback) {
        String raw = lookup.apply(PREFIX + name);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException notANumber) {
            throw new ConfigError(PREFIX + name + " is '" + raw + "', which is not a number");
        }
    }
}
