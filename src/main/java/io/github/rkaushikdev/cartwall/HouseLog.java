package io.github.rkaushikdev.cartwall;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Log lines the office can grep, and nothing that needs a parser.
 *
 * <p>The people who read these are the traffic director at six in the morning and whoever is on
 * call, and both of them have a terminal and no patience. One line per event: a timestamp, a
 * level, an event name, then key equals value pairs.
 *
 * <pre>2025-08-04T05:31:02 info log.released station=KWLO day=2025-08-04 rev=0</pre>
 *
 * <p>The event name is dotted, lower case, and an interface: renaming one breaks somebody's saved
 * search. Values are written so a value with a space in it does not split a field, because an
 * advertiser is called "Black Hawk Bank".
 *
 * <p>Events go through SLF4J with the fields as key-value pairs, so anything that reads SLF4J sees
 * them. {@link #configure} points the package's logger at one stream in the house format.
 */
public final class HouseLog {

    /** The package's logger. Every named logger sits under it. */
    public static final String ROOT = "cartwall";

    /** The environment variable that turns the level up. */
    public static final String LEVEL_VAR = "CARTWALL_LOG_LEVEL";

    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);

    private static final Map<String, Level> LEVELS =
            Map.of("debug", Level.DEBUG, "info", Level.INFO, "warning", Level.WARN, "error", Level.ERROR);

    private HouseLog() {}

    /**
     * One key and its value, in a log line.
     *
     * @param name the key
     * @param value the value, which may be null
     */
    public record Field(String name, Object value) {

        public static Field field(String name, Object value) {
            return new Field(name, value);
        }
    }

    /** One value, written so it cannot split a field. */
    public static String render(Object value) {
        if (value == null) {
            return "-";
        }
        if (value instanceof Boolean flag) {
            return flag ? "yes" : "no";
        }
        String text = value.toString();
        if (text.isEmpty()) {
            return "\"\"";
        }
        boolean spaced = text.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c));
        if (spaced || text.indexOf('"') >= 0) {
            return '"' + text.replace('"', '\'') + '"';
        }
        return text;
    }

    /** Key equals value, in the order they were passed. */
    public static String pairs(Field... fields) {
        return pairs(List.of(fields));
    }

    /** Key equals value, in the order they were passed. */
    public static String pairs(List<Field> fields) {
        return fields.stream()
                .map(field -> field.name() + "=" + render(field.value()))
                .collect(Collectors.joining(" "));
    }

    /** The whole line, stamped now, without the newline. */
    public static String line(String level, String event, Field... fields) {
        return line(LocalDateTime.now(), level, event, List.of(fields));
    }

    static String line(LocalDateTime when, String level, String event, List<Field> fields) {
        String tail = pairs(fields);
        return STAMP.format(when) + " " + level + " " + event + (tail.isEmpty() ? "" : " " + tail);
    }

    /** The package's logger. */
    public static Logger logger() {
        return LoggerFactory.getLogger(ROOT);
    }

    /** A named logger under the package's own, such as {@code cartwall.placer}. */
    public static Logger logger(String name) {
        return LoggerFactory.getLogger(name.isEmpty() ? ROOT : ROOT + "." + name);
    }

    /** Point the package's logger at standard error, at the level the environment asks for. */
    public static void configure() {
        configure(null, System.err, System.getenv()::get);
    }

    /** Point the package's logger at {@code stream}, at the level the environment asks for. */
    public static void configure(OutputStream stream) {
        configure(null, stream, System.getenv()::get);
    }

    /** Point the package's logger at {@code stream}. Safe to call twice. */
    public static void configure(String level, OutputStream stream) {
        configure(level, stream, System.getenv()::get);
    }

    /**
     * Point the package's logger at one handler, replacing whatever was there.
     *
     * @param level debug, info, warning or error; null reads {@link #LEVEL_VAR} through {@code
     *     environment}, and failing that is info
     */
    static void configure(String level, OutputStream stream, UnaryOperator<String> environment) {
        String asked = level != null ? level : environment.apply(LEVEL_VAR);
        String wanted = (asked == null || asked.isEmpty() ? "info" : asked).toLowerCase(Locale.ROOT);
        Level chosen = LEVELS.get(wanted);
        if (chosen == null) {
            throw new IllegalArgumentException("'" + wanted + "' is not a log level");
        }
        ch.qos.logback.classic.Logger house = houseLogger();
        LoggerContext context = house.getLoggerContext();
        house.setLevel(chosen);
        house.setAdditive(false);
        house.detachAndStopAllAppenders();

        LayoutWrappingEncoder<ILoggingEvent> encoder = new LayoutWrappingEncoder<>();
        encoder.setContext(context);
        HouseLayout layout = new HouseLayout();
        layout.setContext(context);
        layout.start();
        encoder.setLayout(layout);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setName("house");
        appender.setEncoder(encoder);
        appender.setImmediateFlush(true);
        appender.setOutputStream(new KeptOpen(stream));
        appender.start();
        house.addAppender(appender);
    }

    /** Whether the package's logger hands its lines on to the root logger as well. */
    public static boolean propagates() {
        return houseLogger().isAdditive();
    }

    /** Whether the package's logging is switched off, as {@link #quietly} does. */
    public static boolean isQuiet() {
        return houseLogger().getLevel() == Level.OFF;
    }

    /**
     * Run {@code block} with the package's logging turned off, and put it back afterwards.
     *
     * <p>For the command line, which prints its own output and does not want the same facts twice.
     */
    public static <T> T quietly(Supplier<T> block) {
        ch.qos.logback.classic.Logger house = houseLogger();
        Level was = house.getLevel();
        house.setLevel(Level.OFF);
        try {
            return block.get();
        } finally {
            house.setLevel(was);
        }
    }

    /** Run {@code block} with the package's logging turned off, and put it back afterwards. */
    public static void quietly(Runnable block) {
        quietly(() -> {
            block.run();
            return null;
        });
    }

    /** The ordinary case. One thing happened and here is what it was. */
    public static void event(String name, Field... fields) {
        emit(logger().atInfo(), name, fields);
    }

    public static void warn(String name, Field... fields) {
        emit(logger().atWarn(), name, fields);
    }

    public static void failed(String name, Field... fields) {
        emit(logger().atError(), name, fields);
    }

    public static void detail(String name, Field... fields) {
        emit(logger().atDebug(), name, fields);
    }

    private static void emit(LoggingEventBuilder builder, String name, Field... fields) {
        for (Field field : fields) {
            builder = builder.addKeyValue(field.name(), field.value());
        }
        builder.log(name);
    }

    private static ch.qos.logback.classic.Logger houseLogger() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        return context.getLogger(ROOT);
    }

    /** A stream an appender may stop without closing what it was handed. */
    private static final class KeptOpen extends FilterOutputStream {

        KeptOpen(OutputStream stream) {
            super(stream);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
