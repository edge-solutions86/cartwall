package io.github.rkaushikdev.cartwall.api;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.Credit;
import io.github.rkaushikdev.cartwall.domain.OrderStatus;
import io.github.rkaushikdev.cartwall.logs.LogState;
import io.github.rkaushikdev.cartwall.playout.Played;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * How the service reads and writes JSON, in one place so the application and the tests of its
 * shapes cannot drift apart.
 *
 * <p>Field names are snake case. A field nobody declared is an error, not a
 * shrug. Dates are ISO strings. The enumerations cross as the short strings the office reads
 * ({@code fixed}, {@code cash in advance}), the same strings the database stores.
 */
public final class Json {

    /** The enumerations that cross the wire, each as its {@code value()}. */
    static final List<ValueEnum<?>> ENUMS = List.of(
            new ValueEnum<>(ClassOfTime.class, ClassOfTime::value, ClassOfTime.values()),
            new ValueEnum<>(OrderStatus.class, OrderStatus::value, OrderStatus.values()),
            new ValueEnum<>(Credit.class, Credit::value, Credit.values()),
            new ValueEnum<>(LogState.class, LogState::value, LogState.values()),
            new ValueEnum<>(Played.class, Played::value, Played.values()));

    private Json() {}

    /** Apply the house's JSON rules to a builder: the service's, or a test's. */
    public static void configure(Jackson2ObjectMapperBuilder builder) {
        builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .featuresToEnable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .modulesToInstall(module());
    }

    /** A mapper built the way the service builds its own. */
    public static ObjectMapper mapper() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        configure(builder);
        return builder.build();
    }

    /** The enumerations as their short strings, both ways. */
    static SimpleModule module() {
        SimpleModule module = new SimpleModule("cartwall");
        ENUMS.forEach(kind -> kind.register(module));
        return module;
    }

    /** One enumeration with a short string of its own. */
    record ValueEnum<E extends Enum<E>>(Class<E> type, Function<E, String> value, E[] members) {

        /** The member written as {@code text}, or empty. */
        E of(String text) {
            for (E member : members) {
                if (value.apply(member).equals(text)) {
                    return member;
                }
            }
            return null;
        }

        /** What a caller is told when they send something else: the strings it accepts. */
        String expected() {
            List<String> quoted = Arrays.stream(members)
                    .map(member -> "'" + value.apply(member) + "'")
                    .toList();
            if (quoted.size() == 1) {
                return "Input should be " + quoted.get(0);
            }
            return "Input should be " + String.join(", ", quoted.subList(0, quoted.size() - 1)) + " or "
                    + quoted.get(quoted.size() - 1);
        }

        void register(SimpleModule module) {
            module.addSerializer(type, new JsonSerializer<E>() {
                @Override
                public void serialize(E member, JsonGenerator out, SerializerProvider provider) throws IOException {
                    out.writeString(value.apply(member));
                }
            });
            module.addDeserializer(type, new JsonDeserializer<E>() {
                @Override
                public E deserialize(JsonParser in, DeserializationContext context) throws IOException {
                    E found = in.currentToken() == JsonToken.VALUE_STRING ? of(in.getText()) : null;
                    if (found == null) {
                        throw new Refused(in, expected());
                    }
                    return found;
                }
            });
        }
    }
}
