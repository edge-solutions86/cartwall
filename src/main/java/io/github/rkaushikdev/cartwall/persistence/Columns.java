package io.github.rkaushikdev.cartwall.persistence;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * How the value types go into a column, and the rule they all follow: nothing is stored in a type
 * that loses information.
 *
 * <p>Money is a whole number of cents, never a float and never a numeric somebody will read into
 * one. Durations are whole tenths. Air times are tenths from midnight rather than a TIME, because
 * a TIME invites a time zone and this domain has none: 7:10 in the log means 7:10 on the
 * transmitter, whatever the server thinks the hour is.
 */
public final class Columns {

    /** Long enough for a campaign name, short enough that nobody pastes an essay. */
    public static final int NAME_LENGTH = 120;

    public static final int CODE_LENGTH = 16;

    private Columns() {}

    /** Money, stored as a whole number of cents. */
    @Converter(autoApply = true)
    public static final class Cents implements AttributeConverter<Money, Integer> {

        @Override
        public Integer convertToDatabaseColumn(Money value) {
            return value == null ? null : Math.toIntExact(value.cents());
        }

        @Override
        public Money convertToEntityAttribute(Integer value) {
            return value == null ? null : new Money(value);
        }
    }

    /** A length, stored as a whole number of tenths of a second. */
    @Converter(autoApply = true)
    public static final class Tenths implements AttributeConverter<Duration, Integer> {

        @Override
        public Integer convertToDatabaseColumn(Duration value) {
            return value == null ? null : value.tenths();
        }

        @Override
        public Duration convertToEntityAttribute(Integer value) {
            return value == null ? null : new Duration(value);
        }
    }

    /** An air time, stored as tenths from midnight. No time zone, on purpose. */
    @Converter(autoApply = true)
    public static final class ClockTenths implements AttributeConverter<AirTime, Integer> {

        @Override
        public Integer convertToDatabaseColumn(AirTime value) {
            return value == null ? null : value.tenths();
        }

        @Override
        public AirTime convertToEntityAttribute(Integer value) {
            return value == null ? null : new AirTime(value);
        }
    }

    /** A sold length, stored as whole seconds. {@code 30} reads as a :30. */
    @Converter(autoApply = true)
    public static final class Seconds implements AttributeConverter<SpotLength, Integer> {

        @Override
        public Integer convertToDatabaseColumn(SpotLength value) {
            return value == null ? null : value.seconds();
        }

        @Override
        public SpotLength convertToEntityAttribute(Integer value) {
            return value == null ? null : new SpotLength(value);
        }
    }
}
