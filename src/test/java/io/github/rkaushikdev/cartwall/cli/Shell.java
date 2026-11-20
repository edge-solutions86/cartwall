package io.github.rkaushikdev.cartwall.cli;

import io.github.rkaushikdev.cartwall.Settings;
import io.github.rkaushikdev.cartwall.persistence.Database;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A terminal for the command line tests: an environment, what the operator types, and what the
 * command printed on each stream.
 */
final class Shell {

    private final Map<String, String> environment;
    private final String typed;

    Shell() {
        this(Map.of(), "");
    }

    private Shell(Map<String, String> environment, String typed) {
        this.environment = environment;
        this.typed = typed;
    }

    /** The same shell with {@code environment} exported. */
    Shell exporting(Map<String, String> exported) {
        return new Shell(exported, typed);
    }

    /** The same shell with {@code text} waiting to be typed at the first prompt. */
    Shell typing(String text) {
        return new Shell(environment, text);
    }

    Result run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Terminal terminal = new Terminal(
                environment::get,
                new BufferedReader(new StringReader(typed)),
                new PrintWriter(out, true),
                new PrintWriter(err, true));
        int code = Main.run(terminal, args);
        return new Result(code, out.toString(), err.toString());
    }

    /** A file database under {@code directory}, named {@code name}. */
    static String fileUrl(Path directory, String name) {
        return "jdbc:h2:file:" + directory.resolve(name);
    }

    /** The tables in the database at {@code url}. */
    static List<String> tablesIn(String url) {
        try (Database database = Database.open(Settings.defaults().withDatabase(url))) {
            return database.tableNames();
        }
    }

    /** Migrate the database at {@code url} and let {@code seed} fill it. */
    static void seed(String url, Consumer<UnitOfWork> seed) {
        try (Database database = Database.open(Settings.defaults().withDatabase(url))) {
            database.migrate();
            work(database, seed);
        }
    }

    /** Open the database at {@code url}, let {@code work} change it, and commit. */
    static void change(String url, Consumer<UnitOfWork> work) {
        try (Database database = Database.open(Settings.defaults().withDatabase(url))) {
            work(database, work);
        }
    }

    private static void work(Database database, Consumer<UnitOfWork> work) {
        try (UnitOfWork uow = database.unitOfWork()) {
            work.accept(uow);
            uow.commit();
        }
    }

    /** What one command line came back with. */
    record Result(int code, String out, String err) {

        /** Both streams, the way a terminal shows them. */
        String output() {
            return out + err;
        }
    }
}
