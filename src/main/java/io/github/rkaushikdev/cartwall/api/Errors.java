package io.github.rkaushikdev.cartwall.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.rkaushikdev.cartwall.CartwallError;
import io.github.rkaushikdev.cartwall.ConflictError;
import io.github.rkaushikdev.cartwall.LookupFailure;
import io.github.rkaushikdev.cartwall.ValidationError;
import io.github.rkaushikdev.cartwall.api.BodyRejected.Problem;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * One error shape, whatever went wrong and wherever it went wrong:
 * {@code {"code": "break_full", "message": "...", "context": {...}}}.
 *
 * <p>The status is a function of the error class and nothing else, which is why the classes were
 * given one root in the first place. A caller can switch on {@code code} and never parse a message,
 * and the message can be rewritten without breaking anybody. Failures the framework finds in a
 * request are folded into the same shape as a {@link BodyRejected}.
 */
@RestControllerAdvice
public class Errors {

    /** Which status each root maps to, tried in order, so the narrow ones come before the broad. */
    static final Map<Class<? extends CartwallError>, Integer> STATUS = statuses();

    private static Map<Class<? extends CartwallError>, Integer> statuses() {
        Map<Class<? extends CartwallError>, Integer> status = new LinkedHashMap<>();
        status.put(NotAuthenticated.class, 401);
        status.put(NotAllowed.class, 403);
        status.put(ValidationError.class, 422);
        status.put(LookupFailure.class, 404);
        status.put(ConflictError.class, 409);
        status.put(CartwallError.class, 500);
        return status;
    }

    public static int statusFor(CartwallError failure) {
        return STATUS.entrySet().stream()
                .filter(entry -> entry.getKey().isInstance(failure))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(500);
    }

    public static Map<String, Object> bodyFor(CartwallError failure) {
        return failure.asMap();
    }

    @ExceptionHandler(CartwallError.class)
    public ResponseEntity<Map<String, Object>> domain(CartwallError failure) {
        return ResponseEntity.status(statusFor(failure)).body(bodyFor(failure));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException failure) {
        Problem problem = failure.getCause() instanceof JsonProcessingException json
                ? BodyRejected.problem("body", json)
                : new Problem("body", Schemas.REQUIRED);
        return domain(new BodyRejected(List.of(problem)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException failure) {
        List<Problem> problems = new ArrayList<>();
        for (FieldError error : failure.getBindingResult().getFieldErrors()) {
            problems.add(new Problem("body." + dotted(error.getField()), error.getDefaultMessage()));
        }
        return domain(new BodyRejected(problems));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> missing(MissingServletRequestParameterException failure) {
        return domain(new BodyRejected(List.of(new Problem("query." + failure.getParameterName(), Schemas.REQUIRED))));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> mismatched(MethodArgumentTypeMismatchException failure) {
        String root = failure.getParameter().hasParameterAnnotation(PathVariable.class) ? "path." : "query.";
        Class<?> wanted = failure.getRequiredType();
        String why = Json.ENUMS.stream()
                .filter(kind -> kind.type() == wanted)
                .map(Json.ValueEnum::expected)
                .findFirst()
                .orElse("Input should be a valid " + (wanted == null ? "value" : kindOf(wanted)));
        return domain(new BodyRejected(List.of(new Problem(root + failure.getName(), why))));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> outOfRange(HandlerMethodValidationException failure) {
        List<Problem> problems = new ArrayList<>();
        failure.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors()
                    .forEach(error -> problems.add(new Problem("query." + name, error.getDefaultMessage())));
        });
        return domain(new BodyRejected(problems));
    }

    /** A binding path as dots: {@code lines[0].perWeek} becomes {@code lines.0.per_week}. */
    static String dotted(String field) {
        return Arrays.stream(field.replaceAll("\\[(\\d+)]", ".$1").split("\\."))
                .map(BodyRejected::snake)
                .collect(Collectors.joining("."));
    }

    private static String kindOf(Class<?> type) {
        if (type == int.class || type == Integer.class) {
            return "integer";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type == LocalDate.class) {
            return "date";
        }
        return type.getSimpleName().toLowerCase(Locale.ROOT);
    }
}
