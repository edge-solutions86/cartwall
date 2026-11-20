package io.github.rkaushikdev.cartwall.testroutes;

import io.github.rkaushikdev.cartwall.ConflictError;
import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.LookupFailure;
import io.github.rkaushikdev.cartwall.api.Work;
import io.github.rkaushikdev.cartwall.scheduling.BreakFull;
import io.github.rkaushikdev.cartwall.services.Numbering;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes the application tests add to see what the application does with them: a domain failure,
 * a miss, a body of the wrong shape, and work that commits or rolls back.
 */
@RestController
public class AppRoutes {

    private final Work work;

    public AppRoutes(Work work) {
        this.work = work;
    }

    /** What the body route takes. */
    public record Body(@NotNull(message = "Field required") Integer count) {}

    @GetMapping("/boom")
    public Map<String, Object> boom() {
        throw new BreakFull("the break is full", Details.of("hour", 7, "number", 2));
    }

    @GetMapping("/missing")
    public Map<String, Object> missing() {
        throw new LookupFailure("order 25-9999 is not on file", Details.of("order", "25-9999"));
    }

    @PostMapping("/take")
    public Map<String, Object> take(@Valid @RequestBody Body body) {
        return Map.of("count", body.count());
    }

    @PostMapping("/counter")
    public Map<String, Object> takeANumber() {
        return work.apply(uow -> Map.of("number", new Numbering(uow.entityManager()).next("ORD", 2025)));
    }

    @PostMapping("/counter/{fail}")
    public Map<String, Object> takeANumberAndMaybeFail(@PathVariable String fail) {
        return work.apply(uow -> {
            String number = new Numbering(uow.entityManager()).next("ORD", 2025);
            if (fail.equals("yes")) {
                throw new ConflictError("changed my mind");
            }
            return Map.of("number", number);
        });
    }
}
