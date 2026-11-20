package io.github.rkaushikdev.cartwall.api;

import io.github.rkaushikdev.cartwall.Details;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The door every route but health and readiness goes through.
 *
 * <p>It runs as an interceptor, before Spring resolves a single argument, so a caller who may not
 * be here is told so before anybody reads what they sent: a 401 or a 403 beats a 422. It also
 * hands the caller to any handler that asks for a {@link Caller}, so a write can put a name on the
 * audit trail.
 */
public final class Gate implements HandlerInterceptor, HandlerMethodArgumentResolver {

    private static final String CALLER = Gate.class.getName() + ".caller";

    private final KeyRing ring;

    public Gate(KeyRing ring) {
        this.ring = ring;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method) {
            Requires needs = method.getMethodAnnotation(Requires.class);
            if (needs != null) {
                Caller caller = caller(request);
                caller.require(needs.value());
                request.setAttribute(CALLER, caller);
            }
        }
        return true;
    }

    /** Who is calling. Refuses when the ring is empty rather than waving everybody through. */
    Caller caller(HttpServletRequest request) {
        if (ring.isEmpty()) {
            throw new NotAuthenticated(
                    "no keys are configured, so nobody can be let in", Details.of("hint", "set " + KeyRing.KEYS_VAR));
        }
        return ring.caller(Optional.ofNullable(request.getHeader(KeyRing.HEADER)));
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Caller.class;
    }

    @Override
    public Caller resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer container,
            NativeWebRequest request,
            WebDataBinderFactory binders) {
        Object caller = request.getAttribute(CALLER, NativeWebRequest.SCOPE_REQUEST);
        if (caller instanceof Caller known) {
            return known;
        }
        throw new IllegalStateException(
                parameter.getExecutable().getName() + " asks for a caller and has no @Requires to check one");
    }
}
