package io.github.rkaushikdev.cartwall.cli;

import io.github.rkaushikdev.cartwall.Cartwall;
import io.github.rkaushikdev.cartwall.CartwallError;
import io.github.rkaushikdev.cartwall.HouseLog;
import io.github.rkaushikdev.cartwall.Settings;
import io.github.rkaushikdev.cartwall.domain.Cluster;
import io.github.rkaushikdev.cartwall.domain.Station;
import io.github.rkaushikdev.cartwall.persistence.Database;
import io.github.rkaushikdev.cartwall.persistence.DatabaseUnavailable;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * The command line, which is what actually runs at four in the morning.
 *
 * <p>The HTTP service is for people. This is for cron, and for the traffic director when the
 * service is not answering, and it is built for that:
 *
 * <ul>
 *   <li>Everything writes to standard output and nothing to standard error unless it failed.
 *   <li>The exit code is the answer: {@link #OK}, {@link #REFUSED} for a refusal the operator can
 *       act on, {@link #MISUSED} for arguments that were wrong. Nothing else.
 *   <li>Every destructive command asks, unless {@code --yes} is given. The ones cron runs are not
 *       destructive, so cron never needs the flag.
 *   <li>The package's own logging is off while a command runs. The command says what it did in
 *       its own words; the same facts twice in two formats is worse than either alone.
 * </ul>
 *
 * <p>No command but {@code serve} starts Spring. Each opens the database the settings name, works,
 * and closes it, so a cron job starts quickly and {@code --database} is honoured before anything
 * connects.
 */
@Command(
        name = "cartwall",
        mixinStandardHelpOptions = true,
        version = "cartwall, version " + Cartwall.VERSION,
        description = "Traffic, continuity and billing for a radio group.",
        subcommands = {
            Main.InitDb.class,
            Main.DropDb.class,
            Main.Status.class,
            Main.Stations.class,
            BillingCommands.Invoice.class,
            BillingCommands.Credit.class,
            BillingCommands.Owing.class,
            LogCommands.Build.class,
            LogCommands.BuildExport.class,
            LogCommands.PostAsRun.class,
            LogCommands.Check.class,
            LogCommands.Approve.class,
            LogCommands.Release.class,
            Serve.class
        })
public final class Main {

    /** It worked. */
    public static final int OK = 0;

    /** A refusal the operator can act on. */
    public static final int REFUSED = 1;

    /** The arguments were wrong. */
    public static final int MISUSED = 2;

    private final Terminal terminal;

    @Option(
            names = {"--database", "-d"},
            description = "Database URL. Beats the environment.")
    private String database;

    private Database opened;

    Main(Terminal terminal) {
        this.terminal = terminal;
    }

    /** The console script. */
    public static void main(String... args) {
        System.exit(run(Terminal.system(), args));
    }

    /** Run one command line with the house rules about output and exit codes. */
    public static int run(Terminal terminal, String... args) {
        Main main = new Main(terminal);
        CommandLine command = new CommandLine(main)
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setOut(terminal.out())
                .setErr(terminal.err())
                .setExecutionExceptionHandler((trouble, line, parsed) -> refused(terminal, trouble));
        try {
            return HouseLog.quietly(() -> command.execute(args));
        } finally {
            main.close();
            terminal.out().flush();
            terminal.err().flush();
        }
    }

    private static int refused(Terminal terminal, Exception trouble) throws Exception {
        if (trouble instanceof CartwallError refusal) {
            terminal.err().println(refusal.code() + ": " + refusal.getMessage());
            return REFUSED;
        }
        if (trouble instanceof DatabaseUnavailable unreachable) {
            terminal.err().println("database unreachable: " + unreachable.getMessage());
            return REFUSED;
        }
        if (trouble instanceof Stopped) {
            terminal.err().println("stopped");
            return REFUSED;
        }
        throw trouble;
    }

    Terminal terminal() {
        return terminal;
    }

    /** The settings, with {@code --database} laid over the environment. */
    Settings settings() {
        Settings settings = Settings.from(terminal.environment());
        return database == null || database.isEmpty() ? settings : settings.withDatabase(database);
    }

    /** The database the settings name, opened the first time a command asks. */
    Database database() {
        if (opened == null) {
            opened = Database.open(settings());
        }
        return opened;
    }

    /** A unit of work on the database. Rolls back unless the command commits. */
    UnitOfWork work() {
        return database().unitOfWork();
    }

    /** Ask before doing something that cannot be undone, and stop on anything but a yes. */
    void confirm(String question) {
        while (true) {
            terminal.out().print(question + " [y/N]: ");
            terminal.out().flush();
            String answer;
            try {
                answer = terminal.in().readLine();
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
            if (answer == null) {
                terminal.out().println();
                throw new Stopped();
            }
            String said = answer.strip().toLowerCase(Locale.ROOT);
            if (said.equals("y") || said.equals("yes")) {
                return;
            }
            if (said.isEmpty() || said.equals("n") || said.equals("no")) {
                throw new Stopped();
            }
            terminal.out().println("Error: invalid input");
        }
    }

    private void close() {
        if (opened != null) {
            opened.close();
        }
    }

    /** Build the schema in an empty database. */
    @Command(name = "init-db", mixinStandardHelpOptions = true, description = "Build the schema in an empty database.")
    static final class InitDb implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Option(names = "--yes", description = "Do not ask.")
        private boolean yes;

        @Override
        public Integer call() {
            String url = main.settings().databaseUrl();
            if (!yes) {
                main.confirm("build the schema in " + url + "?");
            }
            main.database().migrate();
            main.terminal().out().println("schema built in " + url);
            return OK;
        }
    }

    /** Take the schema out. Everything in it goes with it. */
    @Command(
            name = "drop-db",
            mixinStandardHelpOptions = true,
            description = "Take the schema out. Everything in it goes with it.")
    static final class DropDb implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Option(names = "--yes", description = "Do not ask.")
        private boolean yes;

        @Override
        public Integer call() {
            String url = main.settings().databaseUrl();
            if (!yes) {
                main.confirm("DROP every table in " + url + "?");
            }
            main.database().drop();
            main.terminal().out().println("schema dropped from " + url);
            return OK;
        }
    }

    /** What this process is pointed at, and whether it answers. */
    @Command(
            name = "status",
            mixinStandardHelpOptions = true,
            description = "What this process is pointed at, and whether it answers.")
    static final class Status implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Override
        public Integer call() {
            Settings settings = main.settings();
            main.terminal().out().println("market    " + settings.market());
            main.terminal().out().println("database  " + settings.databaseUrl());
            main.terminal().out().println("horizon   " + settings.horizonDays() + " days");
            try {
                main.database().ping();
            } catch (DatabaseUnavailable trouble) {
                main.terminal().err().println("database  unreachable: " + trouble.getMessage());
                return REFUSED;
            }
            main.terminal().out().println("database  answering");
            return OK;
        }
    }

    /** The signals on file, and which of them get a log. */
    @Command(
            name = "stations",
            mixinStandardHelpOptions = true,
            description = "The signals on file, and which of them get a log.")
    static final class Stations implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Override
        public Integer call() {
            Cluster cluster;
            try (UnitOfWork uow = main.work()) {
                cluster = uow.stations().cluster();
            }
            if (cluster.size() == 0) {
                main.terminal().out().println("no stations on file");
                return OK;
            }
            for (Station station : cluster) {
                String carried =
                        station.carries().map(parent -> " carries " + parent).orElse("");
                String logged = station.isSatellite() ? "" : " *";
                main.terminal()
                        .out()
                        .println(String.format(
                                Locale.ROOT,
                                "%-10s %-10s %-10s%s%s",
                                station.call(),
                                station.dial(),
                                station.service().value(),
                                carried,
                                logged));
            }
            main.terminal().out().println();
            main.terminal().out().println(cluster.logged().size() + " of " + cluster.size() + " get a log, marked *");
            return OK;
        }
    }
}
