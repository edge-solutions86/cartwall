package io.github.rkaushikdev.cartwall.api;

import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The service's additions to Spring MVC: the {@link Gate} in front of every route, the caller it
 * hands to handlers, and the enumerations read from a query string by the strings the office uses.
 */
@Component
public class ApiWeb implements WebMvcConfigurer {

    private final Gate gate;

    public ApiWeb(KeyRing ring) {
        this.gate = new Gate(ring);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(gate);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(gate);
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        Json.ENUMS.forEach(kind -> register(registry, kind));
    }

    private static <E extends Enum<E>> void register(FormatterRegistry registry, Json.ValueEnum<E> kind) {
        Converter<String, E> reader = text -> {
            E found = kind.of(text);
            if (found == null) {
                throw new IllegalArgumentException(kind.expected());
            }
            return found;
        };
        registry.addConverter(String.class, kind.type(), reader);
    }
}
