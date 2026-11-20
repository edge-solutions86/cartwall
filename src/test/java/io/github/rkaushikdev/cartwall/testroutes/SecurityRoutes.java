package io.github.rkaushikdev.cartwall.testroutes;

import io.github.rkaushikdev.cartwall.api.Caller;
import io.github.rkaushikdev.cartwall.api.Requires;
import io.github.rkaushikdev.cartwall.api.Role;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One route per rung of the ladder, for the tests of the gate itself. It lives outside the
 * application's packages so that only the tests that import it get its routes.
 */
@RestController
public class SecurityRoutes {

    @GetMapping("/anybody")
    @Requires(Role.READER)
    public Map<String, String> anybody(Caller caller) {
        return Map.of("who", caller.toString());
    }

    @PostMapping("/sell")
    @Requires(Role.SELLER)
    public Map<String, String> sell(Caller caller) {
        return Map.of("who", caller.name());
    }

    @PostMapping("/traffic")
    @Requires(Role.TRAFFIC)
    public Map<String, String> traffic(Caller caller) {
        return Map.of("who", caller.name());
    }

    @PostMapping("/bill")
    @Requires(Role.MANAGER)
    public Map<String, String> bill(Caller caller) {
        return Map.of("who", caller.name());
    }
}
