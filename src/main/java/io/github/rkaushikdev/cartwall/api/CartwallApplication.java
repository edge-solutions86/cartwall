package io.github.rkaushikdev.cartwall.api;

import io.github.rkaushikdev.cartwall.Cartwall;
import io.github.rkaushikdev.cartwall.Settings;
import io.github.rkaushikdev.cartwall.persistence.Database;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * The HTTP service: order entry, logs, avails, accounts and as-run posting for the people in the
 * office, behind a key per person.
 *
 * <p>There is one factory, {@link #run}, rather than a context that builds its own database,
 * because the tests start several applications against several databases and the command line
 * opens the database before anything else happens. The database, the settings and the key ring are
 * handed in and become beans; they are the only shared things. Units of work are per request,
 * repositories are per unit of work, and nothing caches a domain object between requests.
 *
 * <p>Spring Boot's own data source, JPA and Flyway set up are switched off: with H2 on the class
 * path and no data source of its own, Boot would otherwise build an embedded database and migrate
 * that.
 */
@SpringBootApplication(
        exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class
        })
public class CartwallApplication {

    public static final String TITLE = "cartwall";
    public static final String DESCRIPTION = "Traffic, continuity and billing for a radio group";

    /** Start the service over a database somebody else opened and will close. */
    public static ConfigurableApplicationContext run(
            Settings settings, Database database, KeyRing keys, String... args) {
        return run(settings, database, keys, List.of(), args);
    }

    /**
     * Start the service with more configuration beside the application's own: the routes a test
     * adds, for instance.
     */
    public static ConfigurableApplicationContext run(
            Settings settings, Database database, KeyRing keys, List<Class<?>> extra, String... args) {
        List<Class<?>> sources = new ArrayList<>();
        sources.add(CartwallApplication.class);
        sources.addAll(extra);
        SpringApplication application = new SpringApplicationBuilder(sources.toArray(Class<?>[]::new))
                .initializers(context -> {
                    context.getBeanFactory().registerSingleton("settings", settings);
                    context.getBeanFactory().registerSingleton("database", database);
                    context.getBeanFactory().registerSingleton("keyRing", keys);
                })
                .build();
        return application.run(args);
    }

    @Bean
    Work work(Database database) {
        return new Work(database);
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer houseJson() {
        return Json::configure;
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title(TITLE).description(DESCRIPTION).version(Cartwall.VERSION));
    }
}
