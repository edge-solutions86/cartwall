package io.github.rkaushikdev.cartwall.cli;

import static io.github.rkaushikdev.cartwall.cli.Main.MISUSED;
import static io.github.rkaushikdev.cartwall.cli.Main.OK;
import static io.github.rkaushikdev.cartwall.cli.Main.REFUSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.Cartwall;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.HouseLog;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTest {

    private static final String NOWHERE = "jdbc:h2:file:/nowhere/at/all/cli";

    @TempDir
    Path tmp;

    private String url;
    private final Shell shell = new Shell();

    @BeforeEach
    void pickADatabase() {
        url = Shell.fileUrl(tmp, "cli");
    }

    private String ready() {
        Shell.seed(url, uow -> uow.stations().saveCluster(Fixtures.cluster()));
        return url;
    }

    @Test
    void theVersionIsThePackagesVersion() {
        Shell.Result result = shell.run("--version");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains(Cartwall.VERSION);
    }

    @Test
    void helpListsTheCommands() {
        Shell.Result result = shell.run("--help");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("init-db", "drop-db", "status", "stations");
    }

    @Test
    void buildingTheSchemaAsksFirst() {
        Shell.Result result = shell.typing("n\n").run("-d", url, "init-db");
        assertThat(result.code()).isNotZero();
        assertThat(Shell.tablesIn(url)).isEmpty();
    }

    @Test
    void buildingTheSchemaWhenToldTo() {
        Shell.Result result = shell.run("-d", url, "init-db", "--yes");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("schema built");
        assertThat(Shell.tablesIn(url)).contains("station");
    }

    @Test
    void answeringYesAtThePromptBuildsIt() {
        Shell.Result result = shell.typing("y\n").run("-d", url, "init-db");
        assertThat(result.code()).isZero();
        assertThat(Shell.tablesIn(url)).contains("station");
    }

    @Test
    void droppingTheSchemaAsksFirst() {
        String ready = ready();
        Shell.Result result = shell.typing("n\n").run("-d", ready, "drop-db");
        assertThat(result.code()).isNotZero();
        assertThat(Shell.tablesIn(ready)).contains("station");
    }

    @Test
    void droppingTheSchemaWhenToldTo() {
        String ready = ready();
        Shell.Result result = shell.run("-d", ready, "drop-db", "--yes");
        assertThat(result.code()).isZero();
        assertThat(Shell.tablesIn(ready)).isEmpty();
    }

    @Test
    void statusSaysWhatItIsPointedAt() {
        String ready = ready();
        Shell.Result result = shell.run("-d", ready, "status");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("Waterloo", ready, "14 days", "database  answering");
    }

    @Test
    void statusSaysWhenTheDatabaseWillNotAnswer() {
        Shell.Result result = shell.run("-d", NOWHERE, "status");
        assertThat(result.code()).isEqualTo(REFUSED);
        assertThat(result.output()).contains("unreachable");
    }

    @Test
    void theStationListMarksTheOnesThatGetALog() {
        Shell.Result result = shell.run("-d", ready(), "stations");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("KWLO", "99.1 FM", "carries KWLO", "5 of 6 get a log");
    }

    @Test
    void theStationListOfAnEmptyDatabase() {
        Shell.seed(url, uow -> {});
        Shell.Result result = shell.run("-d", url, "stations");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("no stations on file");
    }

    @Test
    void theDatabaseCanBeOverriddenOnTheCommandLine() {
        String right = Shell.fileUrl(tmp, "right");
        Shell.Result result = shell.exporting(Map.of("CARTWALL_DATABASE_URL", Shell.fileUrl(tmp, "wrong")))
                .run("-d", right, "init-db", "--yes");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("right");
    }

    @Test
    void aDateThatIsNotADateSaysTheShapeItWants() {
        assertThat(Days.parseDay("2025-08-04")).hasToString("2025-08-04");
        assertThatThrownBy(() -> Days.parseDay("4 August")).hasMessageContaining("use YYYY-MM-DD");
    }

    @Test
    void theExitCodesAreTheThreeTheHouseUses() {
        assertThat(shell.run("-d", url, "init-db", "--yes").code()).isEqualTo(OK);
        assertThat(shell.run("-d", url, "--help").code()).isEqualTo(OK);
        assertThat(shell.run("not-a-command").code()).isEqualTo(MISUSED);
        assertThat(shell.run("-d", NOWHERE, "status").code()).isEqualTo(REFUSED);
    }

    @Test
    void stoppingAtAPromptIsARefusalAndNotACrash() {
        Shell.Result result = shell.run("-d", url, "init-db");
        assertThat(result.code()).isEqualTo(REFUSED);
        assertThat(result.err()).contains("stopped");
    }

    @Test
    void thePackagesOwnLoggingStaysQuietWhileACommandRuns() {
        ByteArrayOutputStream caught = new ByteArrayOutputStream();
        HouseLog.configure("info", caught);
        try {
            Shell.Result result = shell.run("-d", url, "init-db", "--yes");
            HouseLog.event("after.the.command");
            String logged = caught.toString(StandardCharsets.UTF_8);
            assertThat(result.out()).contains("schema built");
            assertThat(logged).contains("after.the.command");
            assertThat(logged).doesNotContain("init");
        } finally {
            HouseLog.configure("info", new ByteArrayOutputStream());
        }
    }
}
