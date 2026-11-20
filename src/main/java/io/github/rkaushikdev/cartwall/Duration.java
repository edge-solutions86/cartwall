package io.github.rkaushikdev.cartwall;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A length of air, in tenths of a second. Never negative.
 *
 * <p>A length is not a position: you can add a length to an {@link AirTime} and get an air time,
 * but adding two air times is meaningless and the types say so. Everything is held as an integer
 * number of tenths, because cut lengths come back off the automation as {@code 29.7} and {@code
 * 30.2} and floating point drifts over a four hour music log.
 *
 * @param tenths the length, in tenths of a second
 */
public record Duration(int tenths) implements Comparable<Duration> {

    public static final int TENTHS_PER_SECOND = 10;
    public static final int SECONDS_PER_MINUTE = 60;
    public static final int SECONDS_PER_HOUR = 3600;
    public static final int SECONDS_PER_DAY = 86400;
    public static final int TENTHS_PER_DAY = SECONDS_PER_DAY * TENTHS_PER_SECOND;

    /** No air at all. */
    public static final Duration ZERO = new Duration(0);

    private static final Pattern WRITTEN = Pattern.compile("^(?:(\\d+):)?(?:(\\d+):)?(\\d+)(?:\\.(\\d))?$");

    public Duration {
        if (tenths < 0) {
            throw new TimeError("a duration cannot run backwards");
        }
    }

    public static Duration ofSeconds(int seconds) {
        return new Duration(seconds * TENTHS_PER_SECOND);
    }

    /**
     * {@code :30}, {@code 30}, {@code 1:00}, {@code 1:00.5} and {@code 00:01:30} all work.
     *
     * <p>The leading colon is how the sales side writes a thirty, and it means the same thing as a
     * bare thirty.
     */
    public static Duration parse(String text) {
        String cleaned = stripLeadingColons(text.strip());
        Matcher match = WRITTEN.matcher(cleaned);
        if (!match.matches()) {
            throw new TimeError("'" + text + "' is not a length");
        }
        List<String> parts = new ArrayList<>();
        for (int group = 1; group <= 3; group++) {
            if (match.group(group) != null) {
                parts.add(match.group(group));
            }
        }
        int seconds = 0;
        for (String part : parts) {
            seconds = seconds * SECONDS_PER_MINUTE + Integer.parseInt(part);
        }
        int tenth = match.group(4) == null ? 0 : Integer.parseInt(match.group(4));
        return new Duration(seconds * TENTHS_PER_SECOND + tenth);
    }

    /** The sum of a run of lengths. */
    public static Duration total(List<Duration> lengths) {
        return new Duration(lengths.stream().mapToInt(Duration::tenths).sum());
    }

    /** The length in seconds, with the tenth. */
    public double seconds() {
        return tenths / (double) TENTHS_PER_SECOND;
    }

    /** Rounded up. A cut that runs 30.1 occupies 31 seconds of a break. */
    public int wholeSeconds() {
        return Math.ceilDiv(tenths, TENTHS_PER_SECOND);
    }

    public Duration plus(Duration other) {
        return new Duration(tenths + other.tenths);
    }

    public Duration minus(Duration other) {
        return new Duration(tenths - other.tenths);
    }

    public Duration times(int count) {
        return new Duration(tenths * count);
    }

    public boolean isZero() {
        return tenths == 0;
    }

    public boolean isAfter(Duration other) {
        return tenths > other.tenths;
    }

    public boolean isBefore(Duration other) {
        return tenths < other.tenths;
    }

    @Override
    public int compareTo(Duration other) {
        return Integer.compare(tenths, other.tenths);
    }

    /** {@code :30}, {@code 1:00.5}, {@code 1:02:05}: the way the log prints a length. */
    @Override
    public String toString() {
        int total = tenths / TENTHS_PER_SECOND;
        int tenth = tenths % TENTHS_PER_SECOND;
        int seconds = total % SECONDS_PER_MINUTE;
        int minutes = total / SECONDS_PER_MINUTE % SECONDS_PER_MINUTE;
        int hours = total / SECONDS_PER_HOUR;
        String fraction = tenth == 0 ? "" : "." + tenth;
        if (hours > 0) {
            return "%d:%02d:%02d%s".formatted(hours, minutes, seconds, fraction);
        }
        if (minutes > 0) {
            return "%d:%02d%s".formatted(minutes, seconds, fraction);
        }
        return ":%02d%s".formatted(seconds, fraction);
    }

    private static String stripLeadingColons(String text) {
        int start = 0;
        while (start < text.length() && text.charAt(start) == ':') {
            start++;
        }
        return text.substring(start);
    }
}
