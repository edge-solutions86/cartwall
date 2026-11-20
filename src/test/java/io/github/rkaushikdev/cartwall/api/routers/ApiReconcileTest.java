package io.github.rkaushikdev.cartwall.api.routers;

import static io.github.rkaushikdev.cartwall.Fixtures.CARD_DATE;
import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.api.Http;
import io.github.rkaushikdev.cartwall.api.KeyRing;
import io.github.rkaushikdev.cartwall.api.TestService;
import io.github.rkaushikdev.cartwall.domain.Cut;
import io.github.rkaushikdev.cartwall.domain.DaypartGrid;
import io.github.rkaushikdev.cartwall.persistence.Database;
import io.github.rkaushikdev.cartwall.persistence.TestDatabase;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.services.Audit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest
@Import({TestService.class, ApiReconcileTest.Keys.class})
class ApiReconcileTest {

    static final String COLUMNS = "date,time,cart,title,length,status,reference";

    @TestConfiguration(proxyBeanMethods = false)
    static class Keys {

        @Bean
        KeyRing keyRing() {
            return KeyRing.parse("eyre:reader:readerkey;tronan:traffic:traffickey;mstone:manager:managerkey");
        }
    }

    @Autowired
    private MockMvc mvc;

    private Database engine;
    private Http reader;
    private Http traffic;
    private Http manager;

    /** One spot on Monday's log, as the log route reads it back. */
    private record Scheduled(String at, String cart, String order, int line) {}

    @BeforeEach
    void putAnOrderOnTheWeeksLogs() {
        engine = TestDatabase.seeded(uow -> {
            uow.stations().saveCluster(Fixtures.cluster());
            uow.accounts().saveBook(Fixtures.accounts());
            for (String call : ApiLogsTest.LOGGED) {
                uow.cards().file(Fixtures.cards().inForce(call, CARD_DATE));
                uow.stations().saveDayparts(call, DaypartGrid.standardGrid());
                uow.stations().saveGrid(Fixtures.programming().get(call));
            }
            uow.accounts().fileCut(new Cut("C1204", "HYVE2501", "HYVEE", THIRTY, new Duration(300), MONDAY));
        });
        Http client = new Http(mvc);
        reader = client.as("readerkey");
        traffic = client.as("traffickey");
        manager = client.as("managerkey");

        Map<String, Object> flight = new LinkedHashMap<>();
        flight.put("first", "2025-08-04");
        flight.put("last", "2025-08-08");
        flight.put("days", "M-F");
        flight.put("per_week", 10);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("number", 1);
        line.put("station", "KWLO");
        line.put("daypart", "AMD");
        line.put("grade", "fixed");
        line.put("length", ":30");
        line.put("rate", "60.00");
        line.put("carts", List.of("C1204"));
        line.put("flight", flight);
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("number", "25-1044");
        order.put("advertiser", "HYVEE");
        order.put("written", "2025-07-21");
        order.put("salesperson", "DHOLM");
        order.put("lines", List.of(line));
        traffic.postJson("/orders", order);
        traffic.post("/orders/25-1044/confirm");
        traffic.post("/logs/build/2025-08-04");
    }

    /** The spots the log actually carries, so the as-run can answer them. */
    private List<Scheduled> scheduled() {
        JsonNode body = reader.get("/logs/KWLO/2025-08-04", "spots", "true").json();
        List<Scheduled> rows = new ArrayList<>();
        body.get("lines").forEach(row -> {
            if (row.get("order").asText().equals("25-1044")) {
                rows.add(new Scheduled(
                        row.get("at").asText(),
                        row.get("cart").asText(),
                        row.get("order").asText(),
                        row.get("line").asInt()));
            }
        });
        return rows;
    }

    private static String aFile(List<String> lines) {
        List<String> all = new ArrayList<>();
        all.add(COLUMNS);
        all.addAll(lines);
        return String.join("\n", all) + "\n";
    }

    private static String aFile(String... lines) {
        return aFile(List.of(lines));
    }

    /** One as-run line answering a scheduled spot, with room to spoil it. */
    private static String aLine(Scheduled row, String at, String status, String reference, String day) {
        String when = at != null ? at : row.at();
        String echoed = reference != null ? reference : row.order() + "-" + row.line();
        return day + "," + when + "," + row.cart() + ",Hy-Vee sale,30.0," + status + "," + echoed;
    }

    private static String aLine(Scheduled row) {
        return aLine(row, null, "played", null, "2025-08-04");
    }

    private String played(int drop) {
        List<Scheduled> rows = scheduled();
        List<Scheduled> kept = rows.subList(0, rows.size() - drop);
        return aFile(kept.stream().map(ApiReconcileTest::aLine).toList());
    }

    private String played() {
        return played(0);
    }

    /** A log only reaches aired through approved and released. */
    private Http.Answer air() {
        traffic.postJson("/logs/KWLO/2025-08-04/approve", Map.of("who", "tronan"));
        traffic.postJson("/logs/KWLO/2025-08-04/release", Map.of("who", "tronan"));
        return manager.post("/reconcile/KWLO/2025-08-04/aired");
    }

    private JsonNode check(String text) {
        return traffic.postText("/reconcile/KWLO/2025-08-04/check", text).json();
    }

    private Http.Answer readOrder(String first, String last, String... more) {
        List<String> query = new ArrayList<>(List.of("first", first, "last", last));
        query.addAll(List.of(more));
        return reader.get("/reconcile/order/25-1044", query.toArray(String[]::new));
    }

    @Test
    void theFixturePutsTwoSpotsOnMonday() {
        assertThat(scheduled()).hasSize(2);
    }

    @Test
    void checkingAnAsRunNeedsTraffic() {
        String text = played();
        assertThat(reader.postText("/reconcile/KWLO/2025-08-04/check", text).status())
                .isEqualTo(403);
        assertThat(traffic.postText("/reconcile/KWLO/2025-08-04/check", text).status())
                .isEqualTo(200);
    }

    @Test
    void aFileThatAnswersEverySpotIsClean() {
        JsonNode body = check(played());
        assertThat(body.get("clean").asBoolean()).isTrue();
        assertThat(body.get("scheduled").asInt()).isEqualTo(2);
        assertThat(body.get("aired").asInt()).isEqualTo(2);
        assertThat(body.get("missed")).isEmpty();
        assertThat(body.get("extra")).isEmpty();
    }

    @Test
    void aMatchedSpotSaysItWasFoundByReference() {
        JsonNode first = check(played()).get("matched").get(0);
        assertThat(first.get("by_reference").asBoolean()).isTrue();
        assertThat(first.get("order").asText()).isEqualTo("25-1044");
        assertThat(first.get("drift_seconds").asInt()).isZero();
        assertThat(first.get("on_time").asBoolean()).isTrue();
        assertThat(first.get("status").asText()).isEqualTo("played");
    }

    @Test
    void aSpotTheAutomationNeverRanComesBackMissed() {
        JsonNode body = check(played(1));
        assertThat(body.get("clean").asBoolean()).isFalse();
        assertThat(body.get("missed")).hasSize(1);
        assertThat(body.get("missed").get(0).get("order").asText()).isEqualTo("25-1044");
        assertThat(body.get("missed").get(0).get("reason").asText()).isEqualTo("no as-run line");
    }

    @Test
    void theOwedTallyCountsTheLineShort() {
        JsonNode body = check(played(2));
        assertThat(body.get("owed")).hasSize(1);
        assertThat(Http.asMap(body.get("owed").get(0))).isEqualTo(Map.of("order", "25-1044", "line", 1, "spots", 2));
    }

    @Test
    void aSpotThatDriftedAnHourIsMovedAndStillAired() {
        List<Scheduled> rows = scheduled();
        AirTime moved = AirTime.parse(rows.get(0).at()).plus(Duration.ofSeconds(3600));
        String text = aFile(aLine(rows.get(0), moved.twelveHour(), "played", null, "2025-08-04"), aLine(rows.get(1)));
        JsonNode body = check(text);
        assertThat(body.get("aired").asInt()).isEqualTo(2);
        assertThat(body.get("missed")).isEmpty();
        assertThat(body.get("clean").asBoolean()).isFalse();
        List<JsonNode> drifted = new ArrayList<>();
        body.get("matched").forEach(row -> {
            if (row.get("drift_seconds").asInt() != 0) {
                drifted.add(row);
            }
        });
        assertThat(drifted.get(0).get("drift_seconds").asInt()).isEqualTo(3600);
        assertThat(drifted.get(0).get("on_time").asBoolean()).isFalse();
    }

    @Test
    void aCartNobodyLoggedComesBackAsExtra() {
        List<String> lines = new ArrayList<>(
                scheduled().stream().map(ApiReconcileTest::aLine).toList());
        Scheduled stray = new Scheduled("3:20:00p", "C9999", "", 0);
        lines.add(aLine(stray, null, "played", "", "2025-08-04"));
        JsonNode body = check(aFile(lines));
        List<String> carts = new ArrayList<>();
        body.get("extra").forEach(row -> carts.add(row.get("cart").asText()));
        assertThat(carts).containsExactly("C9999");
        assertThat(body.get("extra").get(0).get("aired_at").asText()).isEqualTo("3:20:00p");
    }

    @Test
    void aSpotWithNoReferenceStillMatchesOnCartAndTime() {
        String text = aFile(scheduled().stream()
                .map(row -> aLine(row, null, "played", "", "2025-08-04"))
                .toList());
        JsonNode body = check(text);
        assertThat(body.get("scheduled").asInt()).isEqualTo(2);
        assertThat(body.get("missed")).isEmpty();
        List<Boolean> byReference = new ArrayList<>();
        body.get("matched")
                .forEach(row -> byReference.add(row.get("by_reference").asBoolean()));
        assertThat(byReference).containsExactly(false, false);
    }

    @Test
    void aFileForTheWrongDayIsRefused() {
        String text = aFile(scheduled().stream()
                .map(row -> aLine(row, null, "played", null, "2025-08-05"))
                .toList());
        Http.Answer response = traffic.postText("/reconcile/KWLO/2025-08-04/check", text);
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("code").asText()).isEqualTo("bad_reconcile");
    }

    @Test
    void aFileWithTheWrongColumnsIsRefused() {
        Http.Answer response = traffic.postText("/reconcile/KWLO/2025-08-04/check", "when,what\n1,2\n");
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("code").asText()).isEqualTo("bad_asrun");
    }

    @Test
    void aDayNobodyBuiltHasNothingToCompare() {
        Http.Answer response = traffic.postText("/reconcile/KWLO/2025-09-08/check", aFile());
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("message").asText()).contains("no log");
    }

    @Test
    void postingAgainstADayThatHasNotAiredIsRefused() {
        Http.Answer response = traffic.postText("/reconcile/KWLO/2025-08-04", played());
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().get("code").asText()).isEqualTo("not_aired");
    }

    @Test
    void aDayThatHasNotAiredCanStillBeForced() {
        Http.Answer response = traffic.postText("/reconcile/KWLO/2025-08-04", played(), "force", "true");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("aired").asInt()).isEqualTo(2);
    }

    @Test
    void sayingTheDayWentOutNeedsAManager() {
        traffic.postJson("/logs/KWLO/2025-08-04/approve", Map.of("who", "tronan"));
        traffic.postJson("/logs/KWLO/2025-08-04/release", Map.of("who", "tronan"));
        assertThat(traffic.post("/reconcile/KWLO/2025-08-04/aired").status()).isEqualTo(403);
        assertThat(manager.post("/reconcile/KWLO/2025-08-04/aired").status()).isEqualTo(200);
    }

    @Test
    void aLogThatNeverWentOutCannotBeCalledAired() {
        Http.Answer response = manager.post("/reconcile/KWLO/2025-08-04/aired");
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().get("message").asText()).contains("did not go to air");
    }

    @Test
    void postingAnAiredDayCountsWhatWentOut() {
        String text = played(1);
        air();
        JsonNode body = traffic.postText("/reconcile/KWLO/2025-08-04", text).json();
        assertThat(body.get("aired").asInt()).isEqualTo(1);
        assertThat(body.get("missed").asInt()).isEqualTo(1);
        assertThat(body.get("moved").asInt()).isZero();
        assertThat(body.get("unlogged").asInt()).isZero();
    }

    @Test
    void checkingWritesNothing() {
        String text = played();
        air();
        traffic.postText("/reconcile/KWLO/2025-08-04/check", text);
        assertThat(readOrder("2025-08-04", "2025-08-08").json()).isEmpty();
    }

    @Test
    void postingLandsOnTheTrail() {
        String text = played();
        air();
        traffic.postText("/reconcile/KWLO/2025-08-04", text);
        List<Audit.Entry> trail;
        try (UnitOfWork uow = engine.unitOfWork()) {
            trail = Audit.about(uow.entityManager(), "log", "KWLO 2025-08-04");
        }
        Audit.Entry last = trail.get(trail.size() - 1);
        assertThat(last.action()).isEqualTo("asrun.posted");
        assertThat(last.actor()).isEqualTo("tronan");
        assertThat(last.detail()).contains("aired=2");
    }

    @Test
    void whatAiredReadsBackAgainstTheOrder() {
        String text = played();
        air();
        traffic.postText("/reconcile/KWLO/2025-08-04", text);
        JsonNode body = readOrder("2025-08-04", "2025-08-08").json();
        assertThat(body).hasSize(2);
        assertThat(body.get(0).get("advertiser").asText()).isEqualTo("HYVEE");
        assertThat(body.get(0).get("status").asText()).isEqualTo("played");
        assertThat(body.get(0).get("aired_at").asText())
                .isEqualTo(body.get(0).get("scheduled_at").asText());
    }

    @Test
    void whatWasMissedReadsBackAgainstTheOrder() {
        String text = played(1);
        air();
        traffic.postText("/reconcile/KWLO/2025-08-04", text);
        JsonNode body = readOrder("2025-08-04", "2025-08-08", "aired", "false").json();
        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("aired_at").asText()).isEmpty();
    }

    @Test
    void aSpanThatRunsBackwardsIsRefused() {
        assertThat(readOrder("2025-08-08", "2025-08-04").status()).isEqualTo(404);
    }

    @Test
    void aRequestWithNoKeyGetsNowhere() {
        assertThat(new Http(mvc)
                        .postText("/reconcile/KWLO/2025-08-04/check", aFile())
                        .status())
                .isEqualTo(401);
    }
}
