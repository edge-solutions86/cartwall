package io.github.rkaushikdev.cartwall.persistence;

import io.github.rkaushikdev.cartwall.domain.Band;
import io.github.rkaushikdev.cartwall.domain.BreakKind;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.Credit;
import io.github.rkaushikdev.cartwall.domain.OrderStatus;
import io.github.rkaushikdev.cartwall.domain.RotationKind;
import io.github.rkaushikdev.cartwall.domain.Service;
import io.github.rkaushikdev.cartwall.domain.Source;
import io.github.rkaushikdev.cartwall.logs.LogState;
import io.github.rkaushikdev.cartwall.persistence.models.Reason;
import io.github.rkaushikdev.cartwall.playout.Played;
import io.github.rkaushikdev.cartwall.services.Cause;
import io.github.rkaushikdev.cartwall.services.Offer;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.function.Function;

/**
 * An enumeration, stored as its short string value: not a database enum type and not an ordinal.
 *
 * <p>Adding a class of time should not be a migration, and an ordinal makes the rows unreadable in
 * a console at two in the morning, which is when somebody reads them. One converter per stored
 * enumeration, each applied wherever that type appears on a row.
 *
 * @param <E> the enumeration
 */
public abstract class EnumValue<E extends Enum<E>> implements AttributeConverter<E, String> {

    private final Function<E, String> written;
    private final Function<String, E> read;

    protected EnumValue(Function<E, String> written, Function<String, E> read) {
        this.written = written;
        this.read = read;
    }

    @Override
    public String convertToDatabaseColumn(E value) {
        return value == null ? null : written.apply(value);
    }

    @Override
    public E convertToEntityAttribute(String value) {
        return value == null ? null : read.apply(value);
    }

    /** A {@link Band} as {@code am}, {@code fm} or {@code translator}. */
    @Converter(autoApply = true)
    public static final class Bands extends EnumValue<Band> {
        public Bands() {
            super(Band::value, Band::fromValue);
        }
    }

    /** A {@link Service} as its short name. */
    @Converter(autoApply = true)
    public static final class Services extends EnumValue<Service> {
        public Services() {
            super(Service::value, Service::fromValue);
        }
    }

    /** A {@link BreakKind} as its short name. */
    @Converter(autoApply = true)
    public static final class BreakKinds extends EnumValue<BreakKind> {
        public BreakKinds() {
            super(BreakKind::value, BreakKind::fromValue);
        }
    }

    /** A {@link Source} as its short name. */
    @Converter(autoApply = true)
    public static final class Sources extends EnumValue<Source> {
        public Sources() {
            super(Source::value, Source::fromValue);
        }
    }

    /** A {@link Credit} standing as its short name. */
    @Converter(autoApply = true)
    public static final class Credits extends EnumValue<Credit> {
        public Credits() {
            super(Credit::value, Credit::fromValue);
        }
    }

    /** A {@link ClassOfTime} as its full name. */
    @Converter(autoApply = true)
    public static final class Grades extends EnumValue<ClassOfTime> {
        public Grades() {
            super(ClassOfTime::value, ClassOfTime::fromValue);
        }
    }

    /** A {@link RotationKind} as its short name. */
    @Converter(autoApply = true)
    public static final class RotationKinds extends EnumValue<RotationKind> {
        public RotationKinds() {
            super(RotationKind::value, RotationKind::fromValue);
        }
    }

    /** An {@link OrderStatus} as its short name. */
    @Converter(autoApply = true)
    public static final class OrderStatuses extends EnumValue<OrderStatus> {
        public OrderStatuses() {
            super(OrderStatus::value, OrderStatus::fromValue);
        }
    }

    /** A {@link LogState} as its short name. */
    @Converter(autoApply = true)
    public static final class LogStates extends EnumValue<LogState> {
        public LogStates() {
            super(LogState::value, LogState::fromValue);
        }
    }

    /** What the automation said, as {@link Played}'s short name. */
    @Converter(autoApply = true)
    public static final class Plays extends EnumValue<Played> {
        public Plays() {
            super(Played::value, Played::fromValue);
        }
    }

    /** A credit's {@link Reason} as its short name. */
    @Converter(autoApply = true)
    public static final class Reasons extends EnumValue<Reason> {
        public Reasons() {
            super(Reason::value, Reason::fromValue);
        }
    }

    /** A debt's {@link Cause} as its short name. */
    @Converter(autoApply = true)
    public static final class Causes extends EnumValue<Cause> {
        public Causes() {
            super(Cause::value, Cause::fromValue);
        }
    }

    /** An {@link Offer} state as its short name. */
    @Converter(autoApply = true)
    public static final class Offers extends EnumValue<Offer> {
        public Offers() {
            super(Offer::value, Offer::fromValue);
        }
    }
}
