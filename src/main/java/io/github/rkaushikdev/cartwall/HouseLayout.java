package io.github.rkaushikdev.cartwall;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.LayoutBase;
import io.github.rkaushikdev.cartwall.HouseLog.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.event.KeyValuePair;

/**
 * Puts the house format on whatever Logback hands over: stamp, level, event, then the pairs.
 *
 * <p>A failure's exception is flattened onto the same line as {@code error=...}, because a
 * multi line stack trace in the normal path is something the office cannot grep.
 */
public final class HouseLayout extends LayoutBase<ILoggingEvent> {

    @Override
    public String doLayout(ILoggingEvent event) {
        List<Field> fields = new ArrayList<>();
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs != null) {
            for (KeyValuePair pair : pairs) {
                fields.add(new Field(pair.key, pair.value));
            }
        }
        IThrowableProxy failure = event.getThrowableProxy();
        if (failure != null) {
            fields.add(new Field("error", failure.getClassName() + ": " + failure.getMessage()));
        }
        LocalDateTime when =
                LocalDateTime.ofInstant(Instant.ofEpochMilli(event.getTimeStamp()), ZoneId.systemDefault());
        return HouseLog.line(when, levelName(event.getLevel()), event.getFormattedMessage(), fields) + "\n";
    }

    private static String levelName(Level level) {
        if (level.isGreaterOrEqual(Level.ERROR)) {
            return "error";
        }
        if (level.isGreaterOrEqual(Level.WARN)) {
            return "warning";
        }
        if (level.isGreaterOrEqual(Level.INFO)) {
            return "info";
        }
        return "debug";
    }
}
