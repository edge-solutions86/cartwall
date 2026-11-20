package io.github.rkaushikdev.cartwall.api;

import io.github.rkaushikdev.cartwall.Cartwall;
import io.github.rkaushikdev.cartwall.Settings;
import io.github.rkaushikdev.cartwall.persistence.Database;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health and readiness, outside the gate. A monitor that has to hold a key is a monitor somebody
 * eventually turns off.
 */
@RestController
@Tag(name = "service")
public class ServiceController {

    private final Settings settings;
    private final Database database;

    public ServiceController(Settings settings, Database database) {
        this.settings = settings;
        this.database = database;
    }

    /** Is the process up. Does not touch the database, on purpose. */
    @GetMapping("/health")
    public Map<String, String> health() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", "up");
        body.put("version", Cartwall.VERSION);
        body.put("market", settings.market());
        return body;
    }

    /** Is the database there. This one does touch it. */
    @GetMapping("/ready")
    public Map<String, String> ready() {
        database.ping();
        return Map.of("status", "ready");
    }
}
