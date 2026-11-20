package io.github.rkaushikdev.cartwall.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.cartwall.persistence.TestDatabase;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@WebMvcTest
@Import({TestService.class, SecurityRoutes.class, ApiSecurityTest.Keys.class})
class ApiSecurityTest {

    static final String RING = "dholm:seller:sellerkey;tronan:traffic:traffickey;mstone:manager:managerkey";

    @TestConfiguration(proxyBeanMethods = false)
    static class Keys {

        @Bean
        KeyRing keyRing() {
            return KeyRing.parse(RING + ";eyre:reader:readerkey");
        }
    }

    @Autowired
    private MockMvc mvc;

    private Http client;

    @BeforeEach
    void emptyTheDatabase() {
        TestDatabase.fresh();
        client = new Http(mvc);
    }

    @Test
    void theRolesAreALadder() {
        assertThat(Role.MANAGER).isGreaterThan(Role.TRAFFIC);
        assertThat(Role.TRAFFIC).isGreaterThan(Role.SELLER);
        assertThat(Role.SELLER).isGreaterThan(Role.READER);
        assertThat(Role.TRAFFIC.can(Role.SELLER)).isTrue();
        assertThat(Role.TRAFFIC.can(Role.TRAFFIC)).isTrue();
        assertThat(Role.SELLER.can(Role.TRAFFIC)).isFalse();
    }

    @Test
    void aRoleIsNamedTheWayTheOfficeNamesIt() {
        assertThat(Role.named("seller")).isSameAs(Role.SELLER);
        assertThat(Role.named(" TRAFFIC ")).isSameAs(Role.TRAFFIC);
        assertThat(Role.MANAGER.label()).isEqualTo("manager");
        assertThatThrownBy(() -> Role.named("boss"))
                .isInstanceOf(NotAuthenticated.class)
                .hasMessageContaining("is not a role");
    }

    @Test
    void aCallerCarriesAPersonAsWellAsARole() {
        Caller caller = new Caller("DHOLM", Role.SELLER);
        assertThat(caller).hasToString("DHOLM (seller)");
        caller.require(Role.READER);
        NotAllowed caught = catchThrowableOfType(NotAllowed.class, () -> caller.require(Role.TRAFFIC));
        assertThat(caught).hasMessageContaining("is seller and this needs traffic");
        assertThat(caught.context()).isEqualTo(Map.of("actor", "DHOLM", "has", "seller", "needs", "traffic"));
    }

    @Test
    void aRingReadsNameRoleAndKey() {
        KeyRing ring = KeyRing.parse(RING);
        assertThat(ring.size()).isEqualTo(3);
        assertThat(ring.caller(Optional.of("sellerkey"))).isEqualTo(new Caller("dholm", Role.SELLER));
        assertThat(ring.caller(Optional.of("traffickey")).role()).isSameAs(Role.TRAFFIC);
    }

    @Test
    void aRingEntryThatIsNotThreeParts() {
        assertThatThrownBy(() -> KeyRing.parse("dholm:sellerkey"))
                .isInstanceOf(NotAuthenticated.class)
                .hasMessageContaining("is not a name, a role and a key");
        assertThatThrownBy(() -> KeyRing.parse("dholm:seller:"))
                .isInstanceOf(NotAuthenticated.class)
                .hasMessageContaining("has no name or no key");
        assertThatThrownBy(() -> KeyRing.parse(":seller:key"))
                .isInstanceOf(NotAuthenticated.class)
                .hasMessageContaining("has no name or no key");
    }

    @Test
    void blankEntriesAndSpacingAreForgiven() {
        KeyRing ring = KeyRing.parse(" dholm : seller : sellerkey ; ; ");
        assertThat(ring.size()).isEqualTo(1);
        assertThat(ring.caller(Optional.of("sellerkey")).name()).isEqualTo("dholm");
    }

    @Test
    void anEmptyRingIsEmptyRatherThanPermissive() {
        KeyRing ring = KeyRing.parse("");
        assertThat(ring.isEmpty()).isTrue();
        assertThatThrownBy(() -> ring.caller(Optional.of("anything"))).isInstanceOf(NotAuthenticated.class);
    }

    @Test
    void theKeysAreStoredHashedAndNeverInTheClear() {
        KeyRing ring = KeyRing.parse(RING);
        assertThat(ring.keys()).doesNotContainKey("sellerkey");
        assertThat(ring.keys()).containsKey(KeyRing.digest("sellerkey"));
    }

    @Test
    void aKeyNobodyIssued() {
        assertThatThrownBy(() -> KeyRing.parse(RING).caller(Optional.of("guess")))
                .isInstanceOf(NotAuthenticated.class)
                .hasMessageContaining("not one of ours");
        assertThatThrownBy(() -> KeyRing.parse(RING).caller(Optional.empty()))
                .isInstanceOf(NotAuthenticated.class)
                .hasMessageContaining("no X-Cartwall-Key header");
    }

    @Test
    void aRingCanComeFromTheEnvironment() {
        Map<String, String> environment = Map.of(KeyRing.KEYS_VAR, RING);
        assertThat(KeyRing.fromEnvironment(environment::get).size()).isEqualTo(3);
        Map<String, String> unset = Map.of();
        assertThat(KeyRing.fromEnvironment(unset::get).isEmpty()).isTrue();
    }

    @Test
    void healthIsOutsideTheGate() {
        assertThat(client.get("/health").status()).isEqualTo(200);
        assertThat(client.get("/ready").status()).isEqualTo(200);
    }

    @Test
    void aRequestWithNoKeyIsA401() {
        Http.Answer response = client.get("/anybody");
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.json().get("code").asText()).isEqualTo("not_authenticated");
    }

    @Test
    void aRequestWithAKeyNobodyIssuedIsA401() {
        Http.Answer response = client.as("guess").get("/anybody");
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.json().get("message").asText()).contains("not one of ours");
    }

    @Test
    void aReaderCanRead() {
        JsonNode body = client.as("readerkey").get("/anybody").json();
        assertThat(body.get("who").asText()).isEqualTo("eyre (reader)");
    }

    @Test
    void aReaderCannotWrite() {
        Http.Answer response = client.as("readerkey").post("/sell");
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.json().get("code").asText()).isEqualTo("not_allowed");
        assertThat(Http.asMap(response.json().get("context")))
                .isEqualTo(Map.of("actor", "eyre", "has", "reader", "needs", "seller"));
    }

    @Test
    void aSellerCanSellAndNotTouchALog() {
        assertThat(Http.asMap(client.as("sellerkey").post("/sell").json())).isEqualTo(Map.of("who", "dholm"));
        assertThat(client.as("sellerkey").post("/traffic").status()).isEqualTo(403);
    }

    @Test
    void trafficCanDoEverythingASellerCan() {
        assertThat(client.as("traffickey").post("/sell").status()).isEqualTo(200);
        assertThat(client.as("traffickey").post("/traffic").status()).isEqualTo(200);
        assertThat(client.as("traffickey").post("/bill").status()).isEqualTo(403);
    }

    @Test
    void aManagerCanDoTheLot() {
        for (String path : new String[] {"/sell", "/traffic", "/bill"}) {
            assertThat(client.as("managerkey").post(path).status()).as(path).isEqualTo(200);
        }
    }

    @Test
    void anApplicationWithNoKeysLetsNobodyIn() {
        Gate gate = new Gate(KeyRing.parse(""));
        MockMvc nobody = MockMvcBuilders.standaloneSetup(new SecurityRoutes())
                .addInterceptors(gate)
                .setCustomArgumentResolvers(gate)
                .setControllerAdvice(new Errors())
                .build();
        Http.Answer response = new Http(nobody).as("anything").get("/anybody");
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.json().get("message").asText()).contains("no keys are configured");
        assertThat(response.json().get("context").get("hint").asText()).isEqualTo("set " + KeyRing.KEYS_VAR);
    }
}
