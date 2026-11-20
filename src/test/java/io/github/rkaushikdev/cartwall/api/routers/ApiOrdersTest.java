package io.github.rkaushikdev.cartwall.api.routers;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.api.Http;
import io.github.rkaushikdev.cartwall.api.KeyRing;
import io.github.rkaushikdev.cartwall.api.TestService;
import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Advertiser;
import io.github.rkaushikdev.cartwall.domain.Credit;
import io.github.rkaushikdev.cartwall.persistence.TestDatabase;
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

/**
 * What the order routes do, not who may call them. Every request carries a manager key so the gate
 * is never the thing under test; the gate itself is tested in {@code ApiRolesTest}.
 */
@WebMvcTest
@Import({TestService.class, ApiOrdersTest.Keys.class})
class ApiOrdersTest {

    static final String MANAGER = "mstone:manager:managerkey";

    @TestConfiguration(proxyBeanMethods = false)
    static class Keys {

        @Bean
        KeyRing keyRing() {
            return KeyRing.parse(MANAGER);
        }
    }

    @Autowired
    private MockMvc mvc;

    private Http client;

    @BeforeEach
    void putTheClusterAndTheBookOnFile() {
        AccountBook accounts = Fixtures.accounts();
        accounts.addAdvertiser(Advertiser.build("SLOW", "Slow Pay").withCredit(Credit.HOLD));
        TestDatabase.seeded(uow -> {
            uow.stations().saveCluster(Fixtures.cluster());
            uow.accounts().saveBook(accounts);
        });
        client = new Http(mvc).as("managerkey");
    }

    /** A line body the way a client writes one. */
    static Map<String, Object> aLine(int number, String station, String rate) {
        Map<String, Object> flight = new LinkedHashMap<>();
        flight.put("first", "2025-08-04");
        flight.put("last", "2025-08-29");
        flight.put("days", "M-F");
        flight.put("per_week", 12);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("number", number);
        line.put("station", station);
        line.put("daypart", "AMD");
        line.put("grade", "fixed");
        line.put("length", ":30");
        line.put("rate", rate);
        line.put("flight", flight);
        return line;
    }

    static Map<String, Object> aLine(int number) {
        return aLine(number, "KWLO", "60.00");
    }

    static Map<String, Object> anOrder(String number, String advertiser, List<Map<String, Object>> lines) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("number", number);
        order.put("advertiser", advertiser);
        order.put("written", "2025-07-21");
        order.put("salesperson", "DHOLM");
        order.put("lines", new ArrayList<>(lines));
        return order;
    }

    static Map<String, Object> anOrder(String number) {
        return anOrder(number, "HYVEE", List.of(aLine(1)));
    }

    static Map<String, Object> anOrder() {
        return anOrder("25-1044");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstLine(Map<String, Object> order) {
        return ((List<Map<String, Object>>) order.get("lines")).get(0);
    }

    @Test
    void writingAnOrderGivesBackWhatWasWritten() {
        Http.Answer response = client.postJson("/orders", anOrder());
        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.json();
        assertThat(body.get("number").asText()).isEqualTo("25-1044");
        assertThat(body.get("status").asText()).isEqualTo("pending");
        assertThat(body.get("value").asText()).isEqualTo("2880.00");
        assertThat(body.get("spots").asInt()).isEqualTo(48);
    }

    @Test
    void anOrderCanBeReadBack() {
        client.postJson("/orders", anOrder());
        JsonNode body = client.get("/orders/25-1044").json();
        assertThat(body.get("advertiser").asText()).isEqualTo("HYVEE");
        assertThat(body.get("lines").get(0).get("flight").get("days").asText()).isEqualTo("M-F");
    }

    @Test
    void readingAnOrderThatIsNotOnFile() {
        Http.Answer response = client.get("/orders/25-9999");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("code").asText()).isEqualTo("not_found");
        assertThat(response.json().get("context").get("order").asText()).isEqualTo("25-9999");
    }

    @Test
    void theSameNumberCannotBeWrittenTwice() {
        client.postJson("/orders", anOrder());
        Http.Answer response = client.postJson("/orders", anOrder());
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().get("code").asText()).isEqualTo("conflict");
        assertThat(response.json().get("message").asText()).contains("already on file");
    }

    @Test
    void aBodyThatIsNotTheRightShapeIsA422() {
        Http.Answer response = client.postJson("/orders", Map.of("number", "25-1044"));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("code").asText()).isEqualTo("invalid");
        assertThat(response.json().get("context").get("problems")).isNotEmpty();
    }

    @Test
    void moneySentAsAJsonNumberIsRefused() {
        Map<String, Object> body = anOrder();
        firstLine(body).put("rate", 60.0);
        Http.Answer response = client.postJson("/orders", body);
        assertThat(response.status()).isEqualTo(422);
        List<String> why = new ArrayList<>();
        response.json()
                .get("context")
                .get("problems")
                .forEach(problem -> why.add(problem.get("why").asText()));
        assertThat(why).anyMatch(text -> text.contains("send money as a string"));
    }

    @Test
    void aLengthTheHouseDoesNotSellIsRefusedByTheDomain() {
        Map<String, Object> body = anOrder();
        firstLine(body).put("length", ":25");
        Http.Answer response = client.postJson("/orders", body);
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("code").asText()).isEqualTo("bad_spot");
    }

    @Test
    void anOrderCanBeRewrittenWhileItIsPending() {
        client.postJson("/orders", anOrder());
        Map<String, Object> revised = anOrder("25-1044", "HYVEE", List.of(aLine(1), aLine(2, "KCRR", "40.00")));
        Http.Answer response = client.putJson("/orders/25-1044", revised);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("lines")).hasSize(2);
        assertThat(client.get("/orders/25-1044").json().get("value").asText()).isEqualTo("4800.00");
    }

    @Test
    void thePathAndTheBodyHaveToAgree() {
        client.postJson("/orders", anOrder());
        Http.Answer response = client.putJson("/orders/25-1044", anOrder("25-2000"));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("code").asText()).isEqualTo("bad_order");
        assertThat(Http.asMap(response.json().get("context"))).isEqualTo(Map.of("path", "25-1044", "body", "25-2000"));
    }

    @Test
    void confirmingAnOrderMakesItSchedulable() {
        client.postJson("/orders", anOrder());
        Http.Answer response = client.post("/orders/25-1044/confirm");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("status").asText()).isEqualTo("confirmed");
        assertThat(client.get("/orders/25-1044").json().get("status").asText()).isEqualTo("confirmed");
    }

    @Test
    void anAdvertiserOnHoldCannotHaveAnOrderConfirmed() {
        client.postJson("/orders", anOrder("25-1044", "SLOW", List.of(aLine(1))));
        Http.Answer response = client.post("/orders/25-1044/confirm");
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().get("message").asText()).contains("credit hold");
        assertThat(client.get("/orders/25-1044").json().get("status").asText()).isEqualTo("pending");
    }

    @Test
    void aConfirmedOrderIsNotRewrittenInPlace() {
        client.postJson("/orders", anOrder());
        client.post("/orders/25-1044/confirm");
        Http.Answer response =
                client.putJson("/orders/25-1044", anOrder("25-1044", "HYVEE", List.of(aLine(1), aLine(2))));
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().get("message").asText()).contains("cancel it rather than rewriting");
    }

    @Test
    void cancellingAnOrderTakesItOutOfTheRun() {
        client.postJson("/orders", anOrder());
        client.post("/orders/25-1044/confirm");
        assertThat(client.post("/orders/25-1044/cancel").json().get("status").asText())
                .isEqualTo("cancelled");
    }

    @Test
    void aCancelledOrderDoesNotComeBack() {
        client.postJson("/orders", anOrder());
        client.post("/orders/25-1044/cancel");
        Http.Answer response = client.post("/orders/25-1044/confirm");
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("message").asText()).contains("was cancelled");
    }

    @Test
    void theOrdersOfOneAdvertiserComeBackInNumberOrder() {
        client.postJson("/orders", anOrder("25-2000"));
        client.postJson("/orders", anOrder("25-1044"));
        client.postJson("/orders", anOrder("25-3000", "SLUMB", List.of(aLine(1))));
        JsonNode body = client.get("/orders", "advertiser", "HYVEE").json();
        List<String> numbers = new ArrayList<>();
        body.forEach(order -> numbers.add(order.get("number").asText()));
        assertThat(numbers).containsExactly("25-1044", "25-2000");
        assertThat(client.get("/orders", "advertiser", "NOBODY").json()).isEmpty();
    }

    @Test
    void listingWithoutAnAdvertiserIsRefused() {
        assertThat(client.get("/orders").status()).isEqualTo(422);
    }

    @Test
    void aPendingOrderCanBeDeleted() {
        client.postJson("/orders", anOrder());
        assertThat(client.delete("/orders/25-1044").status()).isEqualTo(204);
        assertThat(client.get("/orders/25-1044").status()).isEqualTo(404);
    }

    @Test
    void aConfirmedOrderIsCancelledRatherThanDeleted() {
        client.postJson("/orders", anOrder());
        client.post("/orders/25-1044/confirm");
        Http.Answer response = client.delete("/orders/25-1044");
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().get("message").asText()).contains("cancel it rather than deleting");
    }

    @Test
    void deletingSomethingThatIsNotThere() {
        assertThat(client.delete("/orders/25-9999").status()).isEqualTo(404);
    }

    @Test
    void aFailedWriteLeavesNothingBehind() {
        Map<String, Object> body = anOrder();
        firstLine(body).put("length", ":25");
        client.postJson("/orders", body);
        assertThat(client.get("/orders/25-1044").status()).isEqualTo(404);
    }
}
