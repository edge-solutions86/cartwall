package io.github.rkaushikdev.cartwall.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.ValidationError;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.Validator;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A request the framework could not read into the shape a route takes, folded into the house's
 * one error shape rather than let out in the framework's own. A client that has to handle two error
 * shapes will handle one of them badly, and it will be the rare one.
 *
 * <p>The context carries the problems, each saying {@code where} (dotted, from {@code body},
 * {@code query} or {@code path}) and {@code why}.
 */
public class BodyRejected extends ValidationError {

    /** What every rejected request is told. */
    public static final String MESSAGE = "the request body is not the shape this route takes";

    private final transient List<Problem> problems;

    public BodyRejected(List<Problem> problems) {
        super(
                MESSAGE,
                Details.of("problems", problems.stream().map(Problem::asMap).toList()));
        this.problems = List.copyOf(problems);
    }

    /** What was wrong, in the order it was found. */
    public List<Problem> problems() {
        return problems;
    }

    /**
     * Read {@code body} into {@code shape} the way a route does: the JSON rules first, then the
     * shape's constraints. Refuses with every problem found.
     */
    public static <T> T read(ObjectMapper mapper, Validator validator, Object body, Class<T> shape) {
        T read;
        try {
            read = mapper.convertValue(body, shape);
        } catch (IllegalArgumentException unreadable) {
            if (unreadable.getCause() instanceof JsonMappingException mapping) {
                throw new BodyRejected(List.of(problem("body", mapping)));
            }
            throw unreadable;
        }
        Set<ConstraintViolation<T>> violations = validator.validate(read);
        if (!violations.isEmpty()) {
            throw new BodyRejected(problems("body", violations));
        }
        return read;
    }

    /** Where Jackson stopped and why, in the words a caller is shown. */
    public static Problem problem(String root, JsonProcessingException trouble) {
        if (!(trouble instanceof JsonMappingException mapping)) {
            return new Problem(root, "JSON decode error");
        }
        String where = mapping.getPath().stream()
                .map(step -> step.getFieldName() != null ? step.getFieldName() : String.valueOf(step.getIndex()))
                .collect(Collectors.joining("."));
        return new Problem(where.isEmpty() ? root : root + "." + where, why(mapping));
    }

    /** The constraint violations on a shape, ordered by where they were found. */
    public static <T> List<Problem> problems(String root, Set<ConstraintViolation<T>> violations) {
        List<Problem> found = new ArrayList<>();
        for (ConstraintViolation<T> violation : violations) {
            found.add(new Problem(root + "." + dotted(violation.getPropertyPath()), violation.getMessage()));
        }
        found.sort(Comparator.comparing(Problem::where));
        return found;
    }

    /** A bean path as dots: {@code lines[0].rate} becomes {@code lines.0.rate}. */
    static String dotted(Path path) {
        List<String> steps = new ArrayList<>();
        for (Path.Node node : path) {
            if (node.getIndex() != null) {
                steps.add(String.valueOf(node.getIndex()));
            }
            if (node.getName() != null && !node.getName().startsWith("<")) {
                steps.add(snake(node.getName()));
            }
        }
        return String.join(".", steps);
    }

    /** A Java name as the wire spells it: {@code perWeek} is {@code per_week}. */
    static String snake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String why(JsonMappingException mapping) {
        if (mapping instanceof Refused) {
            return mapping.getOriginalMessage();
        }
        if (mapping instanceof UnrecognizedPropertyException) {
            return "Extra inputs are not permitted";
        }
        if (mapping instanceof MismatchedInputException mismatched && mismatched.getTargetType() != null) {
            String wanted = kind(mismatched.getTargetType());
            if (mapping instanceof InvalidFormatException) {
                return "Input should be a valid " + wanted + ", unable to parse the input";
            }
            return "Input should be a valid " + wanted;
        }
        return mapping.getOriginalMessage();
    }

    private static String kind(Class<?> type) {
        if (type == Integer.class || type == int.class || type == Long.class || type == long.class) {
            return "integer";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        }
        if (Temporal.class.isAssignableFrom(type)) {
            return "date";
        }
        if (List.class.isAssignableFrom(type)) {
            return "list";
        }
        if (type == String.class) {
            return "string";
        }
        return "object";
    }

    /** One thing wrong with a request. */
    public record Problem(String where, String why) {

        Map<String, String> asMap() {
            Map<String, String> shape = new LinkedHashMap<>();
            shape.put("where", where);
            shape.put("why", why);
            return shape;
        }
    }
}
