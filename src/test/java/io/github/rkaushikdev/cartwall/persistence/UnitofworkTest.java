package io.github.rkaushikdev.cartwall.persistence;

import static io.github.rkaushikdev.cartwall.Fixtures.anOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.ConfigError;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.LookupFailure;
import io.github.rkaushikdev.cartwall.Settings;
import io.github.rkaushikdev.cartwall.persistence.models.OrderRow;
import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnitofworkTest {

    @TempDir
    Path tmp;

    private Settings settings;
    private Database database;

    @BeforeEach
    void fileTheCluster() {
        settings = Settings.defaults().withDatabase("jdbc:h2:file:" + tmp.resolve("uow"));
        database = Database.open(settings);
        database.migrate();
        try (UnitOfWork uow = database.unitOfWork()) {
            uow.stations().saveCluster(Fixtures.cluster());
            uow.accounts().saveBook(Fixtures.accounts());
            uow.commit();
        }
    }

    @AfterEach
    void closeTheDatabase() {
        database.close();
    }

    private static Settings withHorizon(int days) {
        Settings house = Settings.defaults();
        return new Settings(house.databaseUrl(), house.market(), days, house.echoSql(), house.strictCopy());
    }

    @Test
    void theDefaultsAreTheOnesTheHouseRuns() {
        Settings house = Settings.defaults();
        assertThat(house.horizonDays()).isEqualTo(14);
        assertThat(house.strictCopy()).isTrue();
        assertThat(house.echoSql()).isFalse();
        assertThat(house.isH2()).isTrue();
        assertThat(house.toString()).contains("Waterloo");
    }

    @Test
    void settingsThatCannotBeUsed() {
        assertThatThrownBy(() -> Settings.defaults().withDatabase(""))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("no database was configured");
        assertThatThrownBy(() -> withHorizon(0)).isInstanceOf(ConfigError.class).hasMessageContaining("not a horizon");
        assertThatThrownBy(() -> withHorizon(200))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("further out than any card");
    }

    @Test
    void theEnvironmentIsReadOnceAndParsedStrictly() {
        Settings read = Settings.fromEnvironment(Map.of(
                "CARTWALL_DATABASE_URL", "jdbc:postgresql://host/cartwall",
                "CARTWALL_HORIZON_DAYS", "21",
                "CARTWALL_ECHO_SQL", "yes",
                "CARTWALL_STRICT_COPY", "off"));
        assertThat(read.databaseUrl()).isEqualTo("jdbc:postgresql://host/cartwall");
        assertThat(read.horizonDays()).isEqualTo(21);
        assertThat(read.echoSql()).isTrue();
        assertThat(read.strictCopy()).isFalse();
        assertThat(read.isH2()).isFalse();
    }

    @Test
    void aSettingThatIsNotANumberOrAYesSaysSo() {
        Map<String, String> environment = new HashMap<>(Map.of("CARTWALL_HORIZON_DAYS", "a fortnight"));
        assertThatThrownBy(() -> Settings.fromEnvironment(environment))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("not a number");
        environment.remove("CARTWALL_HORIZON_DAYS");
        environment.put("CARTWALL_ECHO_SQL", "maybe");
        assertThatThrownBy(() -> Settings.fromEnvironment(environment))
                .isInstanceOf(ConfigError.class)
                .hasMessageContaining("not a yes or a no");
    }

    @Test
    void pointingSettingsAtAnotherDatabaseLeavesTheRestAlone() {
        Settings house = Settings.defaults();
        Settings mine = new Settings(house.databaseUrl(), house.market(), 21, false, false);
        Settings moved = mine.withDatabase("jdbc:h2:file:./other");
        assertThat(moved.horizonDays()).isEqualTo(21);
        assertThat(moved.strictCopy()).isFalse();
        assertThat(moved.databaseUrl()).endsWith("other");
    }

    @Test
    void theRepositoriesAllShareOneSession() {
        try (UnitOfWork uow = database.unitOfWork()) {
            assertThat(uow.orders().entityManager()).isSameAs(uow.entityManager());
            assertThat(uow.stations().entityManager()).isSameAs(uow.entityManager());
            assertThat(uow.accounts().entityManager()).isSameAs(uow.entityManager());
            assertThat(uow.cards().entityManager()).isSameAs(uow.entityManager());
        }
    }

    @Test
    void whatOneRepositoryWritesTheNextOneSeesWithoutACommit() {
        try (UnitOfWork uow = database.unitOfWork()) {
            uow.orders().save(anOrder().build());
            uow.flush();
            assertThat(uow.orders().exists("25-1044")).isTrue();
            uow.commit();
        }
    }

    @Test
    void leavingTheBlockWithoutCommittingRollsBack() {
        try (UnitOfWork uow = database.unitOfWork()) {
            uow.orders().save(anOrder().build());
            assertThat(uow.committed()).isFalse();
        }
        try (UnitOfWork uow = database.unitOfWork()) {
            assertThat(uow.orders().exists("25-1044")).isFalse();
        }
    }

    @Test
    void committingMakesItStick() {
        try (UnitOfWork uow = database.unitOfWork()) {
            uow.orders().save(anOrder().build());
            uow.commit();
            assertThat(uow.committed()).isTrue();
        }
        try (UnitOfWork uow = database.unitOfWork()) {
            assertThat(uow.orders().get("25-1044").advertiser()).isEqualTo("HYVEE");
        }
    }

    @Test
    void anExceptionInsideTheBlockRollsBackAndStillRaises() {
        assertThatThrownBy(() -> {
                    try (UnitOfWork uow = database.unitOfWork()) {
                        uow.orders().save(anOrder().build());
                        uow.orders().get("25-9999");
                    }
                })
                .isInstanceOf(LookupFailure.class);
        try (UnitOfWork uow = database.unitOfWork()) {
            assertThat(uow.orders().exists("25-1044")).isFalse();
        }
    }

    @Test
    void anExplicitRollbackUndoesTheWorkAndClearsTheFlag() {
        try (UnitOfWork uow = database.unitOfWork()) {
            uow.orders().save(anOrder().build());
            uow.commit();
            uow.orders().save(anOrder().number("25-2000").build());
            uow.rollback();
            assertThat(uow.committed()).isFalse();
        }
        try (UnitOfWork uow = database.unitOfWork()) {
            assertThat(uow.orders().exists("25-1044")).isTrue();
            assertThat(uow.orders().exists("25-2000")).isFalse();
        }
    }

    @Test
    void aClosedUnitOfWorkWillNotBeUsedAgain() {
        UnitOfWork used;
        try (UnitOfWork uow = database.unitOfWork()) {
            uow.commit();
            used = uow;
        }
        assertThatThrownBy(used::commit)
                .isInstanceOf(UnitOfWorkError.class)
                .hasMessageContaining("already been closed");
        assertThatThrownBy(used::flush).isInstanceOf(UnitOfWorkError.class).hasMessageContaining("already been closed");
    }

    @Test
    void closingTwiceIsHarmless() {
        UnitOfWork used;
        try (UnitOfWork uow = database.unitOfWork()) {
            uow.commit();
            used = uow;
        }
        used.close();
        used.close();
        assertThat(used.committed()).isTrue();
    }

    @Test
    void aUnitOfWorkCanBeBuiltOnASessionDirectly() {
        EntityManager session = database.entityManagerFactory().createEntityManager();
        try (UnitOfWork uow = new UnitOfWork(session)) {
            uow.orders().save(anOrder().build());
            uow.commit();
            assertThat(session.createQuery("select o from OrderRow o", OrderRow.class)
                            .getSingleResult()
                            .getNumber())
                    .isEqualTo("25-1044");
        }
    }

    @Test
    void openingADatabaseWithoutBuildingItFindsNoTables() {
        Settings empty = settings.withDatabase("jdbc:h2:file:" + tmp.resolve("empty"));
        try (Database unbuilt = Database.open(empty)) {
            assertThat(unbuilt.tableNames()).isEmpty();
            unbuilt.migrate();
            assertThat(unbuilt.tableNames()).isNotEmpty();
        }
    }
}
