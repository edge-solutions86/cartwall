package io.github.rkaushikdev.cartwall.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.function.UnaryOperator;

/**
 * What a command talks to: the environment it was started in, the keyboard and the two streams.
 *
 * <p>Everything a command prints goes to {@code out}, and nothing goes to {@code err} unless it
 * failed. A cron job that prints on success sends mail every night, the mail gets filtered, and a
 * real failure goes unread for a month.
 */
public record Terminal(UnaryOperator<String> environment, BufferedReader in, PrintWriter out, PrintWriter err) {

    /** The process's own environment, keyboard and streams. */
    public static Terminal system() {
        Charset charset = Charset.defaultCharset();
        return new Terminal(
                System.getenv()::get,
                new BufferedReader(new InputStreamReader(System.in, charset)),
                new PrintWriter(System.out, true, charset),
                new PrintWriter(System.err, true, charset));
    }
}
