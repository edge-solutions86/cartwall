package io.github.rkaushikdev.cartwall.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.cartwall.Cartwall;
import io.github.rkaushikdev.cartwall.CartwallError;
import io.github.rkaushikdev.cartwall.ConflictError;
import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.LookupFailure;
import io.github.rkaushikdev.cartwall.Settings;
import io.github.rkaushikdev.cartwall.ValidationError;
import io.github.rkaushikdev.cartwall.persistence.Database;
import io.github.rkaushikdev.cartwall.persistence.TestDatabase;
import io.github.rkaushikdev.cartwall.scheduling.BreakFull;
import io.github.rkaushikdev.cartwall.testroutes.AppRoutes;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The application as a whole: started the way {@code cartwall serve} starts it, on a port of its
 * own, with the routes the tests add beside its own.
 */
class ApiAppTest {

    private static final String RANDOM_PORT = "--server.port=0";

    private static ConfigurableApplicationContext app;
    private static Settings settings;

    private Http client;

    @BeforeAll
    static void startTheApplication() {
        settings = Settings.defaults().withDatabase(TestDatabase.URL);
        app = start(settings, TestDatabase.fresh());
    }

    @AfterAll
    static void stopTheApplication() {
        app.close();
    }

    @BeforeEach
    void emptyTheDatabase() {
        TestDatabase.fresh();
        client = clientOf(app);
    }

    private static ConfigurableApplicationContext start(Settings settings, Database database) {
        return CartwallApplication.run(settings, database, KeyRing.parse(""), List.of(AppRoutes.class), RANDOM_PORT);
    }

    private static Http clientOf(ConfigurableApplicationContext context) {
        return new Http(MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .build());
    }

    private static int portOf(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    @Test
    void aValidationFailureIsA422() {
        assertThat(Errors.statusFor(new ValidationError("no"))).isEqualTo(422);
    }

    @Test
    void somethingThatIsNotOnFileIsA404() {
        assertThat(Errors.statusFor(new LookupFailure("no"))).isEqualTo(404);
    }

    @Test
    void aConflictWithTheStateIsA409() {
        assertThat(Errors.statusFor(new ConflictError("no"))).isEqualTo(409);
        assertThat(Errors.statusFor(new BreakFull("no"))).isEqualTo(409);
    }

    @Test
    void anythingElseIsA500() {
        assertThat(Errors.statusFor(new CartwallError("no"))).isEqualTo(500);
    }

    @Test
    void theBodyIsTheCodeTheMessageAndTheContext() {
        BreakFull failure = new BreakFull("the break is full", Details.of("hour", 7, "number", 2));
        assertThat(Errors.bodyFor(failure))
                .isEqualTo(Map.of(
                        "code",
                        "break_full",
                        "message",
                        "the break is full",
                        "context",
                        Map.of("hour", 7, "number", 2)));
    }

    @Test
    void healthAnswersWithoutTouchingTheDatabase() {
        try (Database nothing = Database.open(Settings.defaults().withDatabase("jdbc:h2:mem:nothing-here"));
                ConfigurableApplicationContext bare = start(settings, nothing)) {
            JsonNode body = clientOf(bare).get("/health").json();
            assertThat(body.get("status").asText()).isEqualTo("up");
            assertThat(body.get("version").asText()).isEqualTo(Cartwall.VERSION);
            assertThat(body.get("market").asText()).isEqualTo(settings.market());
        }
    }

    @Test
    void readyAnswersWhenTheDatabaseIsThere() {
        Http.Answer response = client.get("/ready");
        assertThat(response.status()).isEqualTo(200);
        assertThat(Http.asMap(response.json())).isEqualTo(Map.of("status", "ready"));
    }

    @Test
    void theOpenapiDocumentIsServed() {
        JsonNode document = client.get("/openapi.json").json();
        assertThat(document.get("info").get("title").asText()).isEqualTo("cartwall");
        assertThat(document.get("info").get("version").asText()).isEqualTo(Cartwall.VERSION);
        assertThat(document.get("paths").has("/health")).isTrue();
    }

    @Test
    void aDomainFailureComesBackInTheHouseShape() {
        Http.Answer response = client.get("/boom");
        assertThat(response.status()).isEqualTo(409);
        assertThat(Http.asMap(response.json()))
                .isEqualTo(Map.of(
                        "code",
                        "break_full",
                        "message",
                        "the break is full",
                        "context",
                        Map.of("hour", 7, "number", 2)));
    }

    @Test
    void aLookupFailureComesBackAsA404InTheSameShape() {
        Http.Answer response = client.get("/missing");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("code").asText()).isEqualTo("not_found");
        assertThat(Http.asMap(response.json().get("context"))).isEqualTo(Map.of("order", "25-9999"));
    }

    @Test
    void aBodyOfTheWrongShapeComesBackInTheHouseShapeToo() {
        Http.Answer response = client.postJson("/take", Map.of("count", "a lot"));
        assertThat(response.status()).isEqualTo(422);
        JsonNode body = response.json();
        assertThat(body.get("code").asText()).isEqualTo("invalid");
        assertThat(body.get("message").asText()).contains("not the shape this route takes");
        JsonNode first = body.get("context").get("problems").get(0);
        assertThat(first.get("where").asText()).endsWith("count");
        assertThat(first.get("why").asText()).isNotEmpty();
    }

    @Test
    void aHandlerThatReturnsGetsItsWorkCommitted() {
        assertThat(Http.asMap(client.post("/counter").json())).isEqualTo(Map.of("number", "25-1001"));
        assertThat(Http.asMap(client.post("/counter").json())).isEqualTo(Map.of("number", "25-1002"));
    }

    @Test
    void aHandlerThatRaisesGetsItsWorkRolledBack() {
        assertThat(Http.asMap(client.post("/counter/no").json())).isEqualTo(Map.of("number", "25-1001"));
        assertThat(client.post("/counter/yes").status()).isEqualTo(409);
        assertThat(Http.asMap(client.post("/counter/no").json())).isEqualTo(Map.of("number", "25-1002"));
    }

    @Test
    void theApplicationAlsoAnswersOverHttpDirectly() throws IOException, InterruptedException {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + portOf(app) + "/health"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void twoApplicationsCanRunAgainstTwoDatabases(@TempDir Path tmp) {
        List<Database> databases = new ArrayList<>();
        List<ConfigurableApplicationContext> apps = new ArrayList<>();
        try {
            for (String name : List.of("one", "two")) {
                Settings own = Settings.defaults().withDatabase("jdbc:h2:file:" + tmp.resolve(name));
                Database database = Database.open(own);
                database.migrate();
                databases.add(database);
                apps.add(start(own, database));
            }
            assertThat(apps.get(0).getBean(Database.class))
                    .isNotSameAs(apps.get(1).getBean(Database.class));
            for (ConfigurableApplicationContext each : apps) {
                assertThat(clientOf(each).get("/ready").status()).isEqualTo(200);
            }
        } finally {
            apps.forEach(ConfigurableApplicationContext::close);
            databases.forEach(Database::close);
        }
    }
}
