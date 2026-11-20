package io.github.rkaushikdev.cartwall.api.routers;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.api.Http;
import io.github.rkaushikdev.cartwall.api.KeyRing;
import io.github.rkaushikdev.cartwall.api.TestService;
import io.github.rkaushikdev.cartwall.persistence.Database;
import io.github.rkaushikdev.cartwall.persistence.TestDatabase;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.services.Audit;
import java.util.ArrayList;
import java.util.HashMap;
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
@Import({TestService.class, ApiAccountsTest.Keys.class})
class ApiAccountsTest {

    static final String RING =
            "eyre:reader:readerkey;dholm:seller:sellerkey;tronan:traffic:traffickey;mstone:manager:managerkey";

    @TestConfiguration(proxyBeanMethods = false)
    static class Keys {

        @Bean
        KeyRing keyRing() {
            return KeyRing.parse(RING);
        }
    }

    @Autowired
    private MockMvc mvc;

    private Database engine;
    private Http reader;
    private Http seller;
    private Http traffic;
    private Http manager;

    @BeforeEach
    void putTheClusterAndTheBookOnFile() {
        engine = TestDatabase.seeded(uow -> {
            uow.stations().saveCluster(Fixtures.cluster());
            uow.accounts().saveBook(Fixtures.accounts());
        });
        Http client = new Http(mvc);
        reader = client.as("readerkey");
        seller = client.as("sellerkey");
        traffic = client.as("traffickey");
        manager = client.as("managerkey");
    }

    private static Map<String, Object> anAccount(String code, String name, List<String> categories) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", name);
        if (categories != null) {
            body.put("categories", categories);
        }
        return body;
    }

    private static Map<String, Object> aCut(String cart, String isci, int tenths) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cart", cart);
        body.put("isci", isci);
        body.put("advertiser", "HYVEE");
        body.put("length", ":30");
        body.put("actual_tenths", tenths);
        body.put("first_air", "2025-08-04");
        return body;
    }

    private static List<String> texts(JsonNode array) {
        List<String> found = new ArrayList<>();
        array.forEach(item -> found.add(item.asText()));
        return found;
    }

    private static Map<String, JsonNode> byCode(JsonNode body) {
        Map<String, JsonNode> found = new HashMap<>();
        body.forEach(row -> found.put(row.get("code").asText(), row));
        return found;
    }

    private List<Audit.Entry> trail(String code) {
        try (UnitOfWork uow = engine.unitOfWork()) {
            return Audit.about(uow.entityManager(), "advertiser", code);
        }
    }

    @Test
    void anAccountReadsBackWithItsCodesSorted() {
        JsonNode body = reader.get("/accounts/HYVEE").json();
        assertThat(body.get("name").asText()).isEqualTo("Hy-Vee");
        assertThat(texts(body.get("categories"))).containsExactly("FUEL.GAS", "GROCERY");
        assertThat(body.get("agency").isNull()).isTrue();
        assertThat(body.get("credit").asText()).isEqualTo("open");
    }

    @Test
    void directBusinessReadsBackWithNoCommission() {
        JsonNode body = reader.get("/accounts/HYVEE").json();
        assertThat(body.get("commission_rate").asText()).isEqualTo("0");
        assertThat(body.get("payer").asText()).isEqualTo("HYVEE");
    }

    @Test
    void anAgencyAccountReadsBackWithTheAgencyAsPayer() {
        JsonNode body = reader.get("/accounts/DEALF").json();
        assertThat(body.get("agency").asText()).isEqualTo("MOP");
        assertThat(body.get("commission_rate").asText()).isEqualTo("0.15");
        assertThat(body.get("payer").asText()).isEqualTo("MOP");
    }

    @Test
    void anAccountThatIsNotOnFile() {
        Http.Answer response = reader.get("/accounts/NOBODY");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("code").asText()).isEqualTo("no_such_account");
        assertThat(response.json().get("context").get("code").asText()).isEqualTo("NOBODY");
    }

    @Test
    void theAgenciesComeBackInCodeOrder() {
        JsonNode body = reader.get("/accounts").json();
        List<String> codes = new ArrayList<>();
        body.forEach(row -> codes.add(row.get("code").asText()));
        assertThat(codes).containsExactly("CRS", "HSE", "MOP");
        assertThat(body.get(2).get("rate").asText()).isEqualTo("0.15");
        assertThat(body.get(1).get("rate").asText()).isEqualTo("0");
    }

    @Test
    void openingAnAccountNeedsAManager() {
        Map<String, Object> body = anAccount("NEWCO", "New Company", List.of("FURNITURE"));
        assertThat(seller.postJson("/accounts", body).status()).isEqualTo(403);
        assertThat(manager.postJson("/accounts", body).status()).isEqualTo(201);
    }

    @Test
    void anOpenedAccountReadsBack() {
        manager.postJson("/accounts", anAccount("NEWCO", "New Company", List.of("AUTO", "BRAND.KIA")));
        JsonNode body = reader.get("/accounts/NEWCO").json();
        assertThat(texts(body.get("categories"))).containsExactly("AUTO", "BRAND.KIA");
    }

    @Test
    void theSameCodeCannotBeOpenedTwice() {
        Http.Answer response = manager.postJson("/accounts", anAccount("HYVEE", "Hy-Vee again", null));
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().get("message").asText()).contains("already on file");
    }

    @Test
    void aCodeThatIsNotACodeIsRefusedByTheDomain() {
        Http.Answer response = manager.postJson("/accounts", anAccount("lower", "x", null));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("code").asText()).isEqualTo("bad_account");
    }

    @Test
    void openingAnAccountLandsOnTheTrail() {
        manager.postJson("/accounts", anAccount("NEWCO", "New Company", null));
        List<Audit.Entry> trail = trail("NEWCO");
        assertThat(trail.get(0).action()).isEqualTo("advertiser.opened");
        assertThat(trail.get(0).actor()).isEqualTo("mstone");
    }

    @Test
    void theConflictsOfADealerAreTheOtherDealers() {
        JsonNode body = reader.get("/accounts/DEALF/conflicts").json();
        List<String> codes = new ArrayList<>();
        body.forEach(row -> codes.add(row.get("code").asText()));
        assertThat(codes).contains("DEALC", "DEALT");
        assertThat(codes).doesNotContain("HYVEE");
    }

    @Test
    void aConflictSaysWhichCodeItRunsThrough() {
        Map<String, JsonNode> found =
                byCode(reader.get("/accounts/DEALF/conflicts").json());
        assertThat(texts(found.get("DEALC").get("through"))).containsExactly("AUTO");
        assertThat(found.get("DEALC").get("name").asText()).isEqualTo("Community Chevrolet");
    }

    @Test
    void theGroceryConflictsRunThroughTwoCodes() {
        Map<String, JsonNode> found =
                byCode(reader.get("/accounts/HYVEE/conflicts").json());
        assertThat(texts(found.get("CASEY").get("through"))).containsExactlyInAnyOrder("FUEL", "GROCERY");
    }

    @Test
    void anAccountWithNoCodesConflictsWithNobody() {
        manager.postJson("/accounts", anAccount("NEWCO", "New Company", null));
        assertThat(reader.get("/accounts/NEWCO/conflicts").json()).isEmpty();
    }

    @Test
    void puttingAnAccountOnHoldNeedsAManager() {
        assertThat(traffic.put("/accounts/HYVEE/credit", "standing", "hold").status())
                .isEqualTo(403);
        Http.Answer response = manager.put("/accounts/HYVEE/credit", "standing", "hold");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("credit").asText()).isEqualTo("hold");
    }

    @Test
    void aHoldSticksAndKeepsTheRestOfTheAccount() {
        manager.put("/accounts/HYVEE/credit", "standing", "hold");
        JsonNode body = reader.get("/accounts/HYVEE").json();
        assertThat(body.get("credit").asText()).isEqualTo("hold");
        assertThat(texts(body.get("categories"))).containsExactly("FUEL.GAS", "GROCERY");
    }

    @Test
    void aHoldIsRecordedWithWhatItWasBefore() {
        manager.put("/accounts/HYVEE/credit", "standing", "hold");
        List<Audit.Entry> trail = trail("HYVEE");
        Audit.Entry last = trail.get(trail.size() - 1);
        assertThat(last.action()).isEqualTo("advertiser.credit");
        assertThat(last.detail()).contains("was=open", "now=hold");
    }

    @Test
    void aStandingThatIsNotOneIsRefused() {
        assertThat(manager.put("/accounts/HYVEE/credit", "standing", "maybe").status())
                .isEqualTo(422);
    }

    @Test
    void filingCopyNeedsTraffic() {
        Map<String, Object> body = aCut("C1204", "HYVE2501", 299);
        assertThat(seller.postJson("/accounts/copy", body).status()).isEqualTo(403);
        Http.Answer response = traffic.postJson("/accounts/copy", body);
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json().get("actual_tenths").asInt()).isEqualTo(299);
    }

    @Test
    void copyThatRunsTooLongIsRefusedByTheDomain() {
        Http.Answer response = traffic.postJson("/accounts/copy", aCut("C1204", "HYVE2501", 320));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("code").asText()).isEqualTo("bad_spot");
    }

    @Test
    void theCopyOfOneAdvertiserComesBackInCartOrder() {
        traffic.postJson("/accounts/copy", aCut("C1300", "HYVE2503", 300));
        traffic.postJson("/accounts/copy", aCut("C1204", "HYVE2501", 300));
        JsonNode body = reader.get("/accounts/HYVEE/copy").json();
        List<String> carts = new ArrayList<>();
        body.forEach(row -> carts.add(row.get("cart").asText()));
        assertThat(carts).containsExactly("C1204", "C1300");
        assertThat(body.get(0).get("killed").isBoolean()).isTrue();
        assertThat(body.get(0).get("killed").asBoolean()).isFalse();
    }

    @Test
    void anAdvertiserWithNoCopyHasAnEmptyLibrary() {
        assertThat(reader.get("/accounts/SLUMB/copy").json()).isEmpty();
    }
}
