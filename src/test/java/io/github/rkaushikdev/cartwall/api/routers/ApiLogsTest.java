package io.github.rkaushikdev.cartwall.api.routers;

import static io.github.rkaushikdev.cartwall.Fixtures.CARD_DATE;
import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.api.Http;
import io.github.rkaushikdev.cartwall.api.KeyRing;
import io.github.rkaushikdev.cartwall.api.TestService;
import io.github.rkaushikdev.cartwall.domain.Cut;
import io.github.rkaushikdev.cartwall.domain.DaypartGrid;
import io.github.rkaushikdev.cartwall.persistence.TestDatabase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What the log routes do, not who may call them. Every request carries a manager key so the gate
 * is never the thing under test; the gate itself is tested in {@code ApiRolesTest}.
 */
@WebMvcTest
@Import({TestService.class, ApiLogsTest.Keys.class})
class ApiLogsTest {

    static final String SIGNED = "2025-08-01T16:30:00";
    static final List<String> LOGGED = List.of("KWLO", "KOEL-FM", "KCRR", "KXEL", "KCNZ");

    @TestConfiguration(proxyBeanMethods = false)
    static class Keys {

        @Bean
        KeyRing keyRing() {
            return KeyRing.parse("mstone:manager:managerkey");
        }
    }

    @Autowired
    private MockMvc mvc;

    private Http client;

    @BeforeEach
    void putTheClusterItsProgrammingAndItsCopyOnFile() {
        TestDatabase.seeded(uow -> {
            uow.stations().saveCluster(Fixtures.cluster());
            uow.accounts().saveBook(Fixtures.accounts());
            for (String call : LOGGED) {
                uow.cards().file(Fixtures.cards().inForce(call, CARD_DATE));
                uow.stations().saveDayparts(call, DaypartGrid.standardGrid());
                uow.stations().saveGrid(Fixtures.programming().get(call));
            }
            // Production dubs the copy. Traffic names a cart that has been dubbed.
            uow.accounts().fileCut(new Cut("C1204", "HYVE2501", "HYVEE", THIRTY, new Duration(300), MONDAY));
            uow.accounts().fileCut(new Cut("C1205", "HYVE2502", "HYVEE", THIRTY, new Duration(300), MONDAY));
        });
        client = new Http(mvc).as("managerkey");
    }

    static Map<String, Object> aLine(String station, List<String> carts) {
        Map<String, Object> flight = new LinkedHashMap<>();
        flight.put("first", "2025-08-04");
        flight.put("last", "2025-08-08");
        flight.put("days", "M-F");
        flight.put("per_week", 10);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("number", 1);
        line.put("station", station);
        line.put("daypart", "AMD");
        line.put("grade", "fixed");
        line.put("length", ":30");
        line.put("rate", "60.00");
        line.put("carts", carts);
        line.put("flight", flight);
        return line;
    }

    static Map<String, Object> aLine() {
        return aLine("KWLO", List.of("C1204"));
    }

    private void anOrder(List<Map<String, Object>> lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("number", "25-1044");
        body.put("advertiser", "HYVEE");
        body.put("written", "2025-07-21");
        body.put("salesperson", "DHOLM");
        body.put("lines", lines);
        client.postJson("/orders", body);
        client.post("/orders/25-1044/confirm");
    }

    private void anOrder() {
        anOrder(List.of(aLine()));
    }

    private Http.Answer sign(String action, String who) {
        return client.postJson("/logs/KWLO/2025-08-04/" + action, Map.of("who", who));
    }

    private static Set<String> elements(JsonNode body) {
        Set<String> kinds = new HashSet<>();
        body.get("lines").forEach(line -> kinds.add(line.get("element").asText()));
        return kinds;
    }

    @Test
    void buildingAWeekWritesALogForEveryStationDay() {
        anOrder();
        JsonNode body = client.post("/logs/build/2025-08-06").json();
        assertThat(body.get("week").asText()).isEqualTo("week of 2025-08-04");
        assertThat(body.get("placed").asInt()).isEqualTo(10);
        assertThat(body.get("wanted").asInt()).isEqualTo(10);
        assertThat(body.get("complete").asBoolean()).isTrue();
        assertThat(body.get("written").asInt()).isEqualTo(35);
        assertThat(body.get("skipped")).isEmpty();
    }

    @Test
    void aBuiltLogReadsBackInAirOrder() {
        anOrder();
        client.post("/logs/build/2025-08-04");
        JsonNode body = client.get("/logs/KWLO/2025-08-04").json();
        assertThat(body.get("station").asText()).isEqualTo("KWLO");
        assertThat(body.get("state").asText()).isEqualTo("draft");
        assertThat(body.get("revision").asInt()).isZero();
        List<Integer> hours = new ArrayList<>();
        body.get("lines").forEach(line -> hours.add(line.get("hour").asInt()));
        assertThat(hours).isSorted();
        assertThat(body.get("sold_units").asInt()).isEqualTo(2);
    }

    @Test
    void theLogCarriesTheFixedElementsAsWellAsTheSpots() {
        anOrder();
        client.post("/logs/build/2025-08-04");
        Set<String> kinds = elements(client.get("/logs/KWLO/2025-08-04").json());
        assertThat(kinds).contains("legal id", "spot", "unsold");
    }

    @Test
    void salesCanAskForTheSpotsAndNothingElse() {
        anOrder();
        client.post("/logs/build/2025-08-04");
        JsonNode body = client.get("/logs/KWLO/2025-08-04", "spots", "true").json();
        assertThat(elements(body)).containsExactly("spot");
        assertThat(body.get("lines")).hasSize(2);
        assertThat(body.get("lines").get(0).get("advertiser").asText()).isEqualTo("HYVEE");
        assertThat(body.get("lines").get(0).get("cart").asText()).isEqualTo("C1204");
    }

    @Test
    void aLogThatWasNeverBuilt() {
        Http.Answer response = client.get("/logs/KWLO/2025-08-04");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("code").asText()).isEqualTo("not_found");
    }

    @Test
    void aSpotBoughtOnTheTranslatorIsReadOffTheParentsLog() {
        anOrder(List.of(aLine("KWLO-LP", List.of("C1204"))));
        client.post("/logs/build/2025-08-04");
        JsonNode body = client.get("/logs/KWLO-LP/2025-08-04").json();
        assertThat(body.get("station").asText()).isEqualTo("KWLO");
        assertThat(body.get("sold_units").asInt()).isEqualTo(2);
    }

    @Test
    void aLogWithCopyOnEverySpotIsReadyToSign() {
        anOrder();
        client.post("/logs/build/2025-08-04");
        JsonNode body = client.get("/logs/KWLO/2025-08-04/ready").json();
        assertThat(body.get("ready").asBoolean()).isTrue();
        assertThat(body.get("problems")).isEmpty();
        assertThat(body.get("warnings").get(0).get("code").asText()).isEqualTo("unsold");
    }

    @Test
    void aSpotWithNoCopyStopsTheLog() {
        anOrder(List.of(aLine("KWLO", List.of())));
        client.post("/logs/build/2025-08-04");
        JsonNode body = client.get("/logs/KWLO/2025-08-04/ready").json();
        assertThat(body.get("ready").asBoolean()).isFalse();
        assertThat(body.get("problems").get(0).get("code").asText()).isEqualTo("missing_copy");
        assertThat(body.get("problems").get(0).get("detail").asText()).contains("25-1044-1");
    }

    @Test
    void aLogWithAProblemIsNotSigned() {
        anOrder(List.of(aLine("KWLO", List.of())));
        client.post("/logs/build/2025-08-04");
        Http.Answer response = sign("approve", "T. Ronan");
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().get("message").asText()).contains("problem");
    }

    @Test
    void signingACleanLogRecordsWhoSignedIt() {
        anOrder();
        client.post("/logs/build/2025-08-04");
        JsonNode body = client.postJson("/logs/KWLO/2025-08-04/approve", Map.of("who", "T. Ronan", "at", SIGNED))
                .json();
        assertThat(body.get("state").asText()).isEqualTo("approved");
        assertThat(body.get("approved_by").asText()).isEqualTo("T. Ronan");
    }

    @Test
    void aLogHasToBeSignedBySomebody() {
        anOrder();
        client.post("/logs/build/2025-08-04");
        assertThat(sign("approve", "").status()).isEqualTo(409);
    }

    @Test
    void aLogIsNotSignedTwice() {
        anOrder();
        client.post("/logs/build/2025-08-04");
        sign("approve", "T. Ronan");
        Http.Answer response = sign("approve", "T. Ronan");
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().get("message").asText()).contains("not a draft");
    }

    @Test
    void aLogHasToBeSignedBeforeItGoesOut() {
        anOrder();
        client.post("/logs/build/2025-08-04");
        Http.Answer response = sign("release", "T. Ronan");
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().get("message").asText()).contains("not been approved");
    }

    @Test
    void aSignedLogGoesOut() {
        anOrder();
        client.post("/logs/build/2025-08-04");
        sign("approve", "T. Ronan");
        assertThat(sign("release", "T. Ronan").json().get("state").asText()).isEqualTo("released");
    }

    @Test
    void aReleasedDayIsLeftAloneByTheNextBuild() {
        anOrder();
        client.post("/logs/build/2025-08-04");
        sign("approve", "T. Ronan");
        sign("release", "T. Ronan");
        JsonNode body = client.post("/logs/build/2025-08-04").json();
        List<String> skipped = new ArrayList<>();
        body.get("skipped").forEach(entry -> skipped.add(entry.asText()));
        assertThat(skipped).contains("KWLO 2025-08-04 released");
        assertThat(body.get("written").asInt()).isEqualTo(34);
    }

    @Test
    void revisingAReleasedLogOpensItBackUp() {
        anOrder();
        client.post("/logs/build/2025-08-04");
        sign("approve", "T. Ronan");
        sign("release", "T. Ronan");
        JsonNode body = client.post("/logs/KWLO/2025-08-04/revise").json();
        assertThat(body.get("state").asText()).isEqualTo("draft");
        assertThat(body.get("revision").asInt()).isEqualTo(1);
        assertThat(body.get("approved_by").asText()).isEmpty();
    }

    @Test
    void aDraftLogDoesNotNeedARevision() {
        anOrder();
        client.post("/logs/build/2025-08-04");
        Http.Answer response = client.post("/logs/KWLO/2025-08-04/revise");
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().get("message").asText()).contains("does not need a revision");
    }

    @Test
    void aSpanOfDaysListsWhatStateEachOneIsIn() {
        anOrder();
        client.post("/logs/build/2025-08-04");
        sign("approve", "T. Ronan");
        JsonNode body = client.get("/logs/KWLO", "first", "2025-08-04", "last", "2025-08-10")
                .json();
        assertThat(body).hasSize(7);
        assertThat(Http.asMap(body.get(0)))
                .isEqualTo(Map.of("day", "2025-08-04", "state", "approved", "revision", 0, "approved_by", "T. Ronan"));
        assertThat(body.get(1).get("state").asText()).isEqualTo("draft");
    }

    @Test
    void aSpanThatRunsBackwardsIsRefused() {
        Http.Answer response = client.get("/logs/KWLO", "first", "2025-08-10", "last", "2025-08-04");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("message").asText()).contains("runs backwards");
    }
}
