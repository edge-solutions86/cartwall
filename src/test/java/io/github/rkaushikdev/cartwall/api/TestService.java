package io.github.rkaushikdev.cartwall.api;

import io.github.rkaushikdev.cartwall.Settings;
import io.github.rkaushikdev.cartwall.persistence.Database;
import io.github.rkaushikdev.cartwall.persistence.TestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * What the service is handed when a test slice starts it: settings, and the suite's database. The
 * key ring is each test class's own, since which keys exist is what several of them are about.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestService {

    @Bean
    Settings settings() {
        return Settings.defaults().withDatabase(TestDatabase.URL);
    }

    /** The suite's database. The suite closes it, not the context. */
    @Bean(destroyMethod = "")
    Database database() {
        return TestDatabase.fresh();
    }
}
