package io.github.rkaushikdev.cartwall.cli;

import static io.github.rkaushikdev.cartwall.Fixtures.CARD_DATE;
import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.cli.Main.REFUSED;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.Cut;
import io.github.rkaushikdev.cartwall.domain.DaypartGrid;
import io.github.rkaushikdev.cartwall.domain.Flight;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.persistence.LogRepository;
import io.github.rkaushikdev.cartwall.scheduling.DayBoard;
import io.github.rkaushikdev.cartwall.scheduling.Placement;
import io.github.rkaushikdev.cartwall.scheduling.Timed;
import io.github.rkaushikdev.cartwall.services.Houses;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliLogsTest {

    private static final ClassOfTime FIXED = ClassOfTime.FIXED;
    private static final LocalDate FRIDAY = LocalDate.of(2025, 8, 8);
    private static final String HEADER = "date,time,cart,title,length,status,reference";

    @TempDir
    Path tmp;

    private String url;
    private final Shell shell = new Shell();

    @BeforeEach
    void putTheClusterOnFile() {
        url = Shell.fileUrl(tmp, "clilogs");
        Shell.seed(url, uow -> {
            uow.stations().saveCluster(Fixtures.cluster());
            uow.accounts().saveBook(Fixtures.accounts());
            for (String call : List.of("KWLO", "KOEL-FM", "KCRR", "KXEL", "KCNZ")) {
                uow.cards().file(Fixtures.cards().inForce(call, CARD_DATE));
                uow.stations().saveDayparts(call, DaypartGrid.standardGrid());
                uow.stations().saveGrid(Fixtures.programming().get(call));
            }
            // Production dubs the copy before traffic can name a cart on a line.
            uow.accounts().fileCut(new Cut("C1204", "HYVE2501", "HYVEE", THIRTY, new Duration(300), MONDAY));
        });
    }

    private void anOrder(String number, int perWeek, List<String> carts) {
        Shell.change(url, uow -> uow.orders()
                .save(new Order(
                                number,
                                "HYVEE",
                                LocalDate.of(2025, 7, 21),
                                "DHOLM",
                                List.of(new Line(
                                        1,
                                        "KWLO",
                                        "AMD",
                                        FIXED,
                                        THIRTY,
                                        Money.dollars(60),
                                        Flight.build(MONDAY, FRIDAY, "M-F", perWeek),
                                        carts)))
                        .confirmed()));
    }

    private void anOrder() {
        anOrder("25-1044", 5, List.of("C1204"));
    }

    private void anOrderWithNoCopy() {
        anOrder("25-1044", 5, List.of());
    }

    private Shell.Result cartwall(String... args) {
        String[] withDatabase = new String[args.length + 2];
        withDatabase[0] = "-d";
        withDatabase[1] = url;
        System.arraycopy(args, 0, withDatabase, 2, args.length);
        return shell.run(withDatabase);
    }

    private Path watchFolder() throws IOException {
        return Files.createDirectory(tmp.resolve("watch"));
    }

    @Test
    void buildingAWeekSaysWhatItDid() {
        anOrder();
        Shell.Result result = cartwall("build", "2025-08-06");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("week of 2025-08-04", "placed   5 of 5", "written  35 logs");
    }

    @Test
    void buildingNamesTheOrdersItCouldNotFill() {
        anOrder("25-2000", 40, List.of());
        Shell.Result result = cartwall("build", "2025-08-04");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("short    25-2000");
    }

    @Test
    void aDayThatIsNotADateIsAMisuse() {
        Shell.Result result = cartwall("build", "the fourth");
        assertThat(result.code()).isNotZero();
        assertThat(result.output()).contains("YYYY-MM-DD");
    }

    @Test
    void exportingWritesTheAutomationsFormatToTheScreen() {
        anOrder();
        cartwall("build", "2025-08-04");
        Shell.Result result = cartwall("build-export", "KWLO", "2025-08-04");
        assertThat(result.code()).isZero();
        List<String> lines = result.out().lines().toList();
        assertThat(lines.get(0)).startsWith("00 00:00:00 LID");
        assertThat(lines).anyMatch(line -> line.contains(" SPT ") && line.contains("HYVEE"));
    }

    @Test
    void exportingWritesAFileWhenToldWhere() throws IOException {
        anOrder();
        cartwall("build", "2025-08-04");
        Path into = watchFolder();
        Shell.Result result = cartwall("build-export", "KWLO", "2025-08-04", "--into", into.toString());
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("lines written");
        Path written = into.resolve("KWLO250804.LOG");
        assertThat(written).exists();
        assertThat(Files.readString(written, StandardCharsets.UTF_8)).doesNotContain("\r\n");
    }

    @Test
    void exportingATranslatorWritesTheParentsLog() throws IOException {
        anOrder();
        cartwall("build", "2025-08-04");
        Path into = watchFolder();
        cartwall("build-export", "KWLO-LP", "2025-08-04", "--into", into.toString());
        assertThat(into.resolve("KWLO250804.LOG")).exists();
    }

    @Test
    void checkingALogWithCopySaysItIsReady() {
        anOrder();
        cartwall("build", "2025-08-04");
        Shell.Result result = cartwall("check", "KWLO", "2025-08-04");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("ready to sign", "warning  unsold");
    }

    @Test
    void checkingALogWithNoCopyRefusesAndSaysWhy() {
        anOrderWithNoCopy();
        cartwall("build", "2025-08-04");
        Shell.Result result = cartwall("check", "KWLO", "2025-08-04");
        assertThat(result.code()).isEqualTo(REFUSED);
        assertThat(result.output()).contains("problem  missing_copy", "is not ready");
    }

    @Test
    void signingALogRecordsWho() {
        anOrder();
        cartwall("build", "2025-08-04");
        Shell.Result result = cartwall("approve", "KWLO", "2025-08-04", "--who", "T. Ronan");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("approved by T. Ronan");
    }

    @Test
    void signingALogWithAProblemIsRefused() {
        anOrderWithNoCopy();
        cartwall("build", "2025-08-04");
        Shell.Result result = cartwall("approve", "KWLO", "2025-08-04", "--who", "T. Ronan");
        assertThat(result.code()).isEqualTo(REFUSED);
        assertThat(result.output()).contains("problem(s)");
    }

    @Test
    void signingNeedsAName() {
        anOrder();
        cartwall("build", "2025-08-04");
        assertThat(cartwall("approve", "KWLO", "2025-08-04").code()).isNotZero();
    }

    @Test
    void releasingASignedLog() {
        anOrder();
        cartwall("build", "2025-08-04");
        cartwall("approve", "KWLO", "2025-08-04", "--who", "T. Ronan");
        Shell.Result result = cartwall("release", "KWLO", "2025-08-04");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("released at revision 0");
    }

    @Test
    void postingAnAsRunSaysWhatAired() throws IOException {
        anOrder();
        cartwall("build", "2025-08-04");
        cartwall("approve", "KWLO", "2025-08-04", "--who", "T. Ronan");
        cartwall("release", "KWLO", "2025-08-04");
        AtomicReference<DayBoard> board = new AtomicReference<>();
        Shell.change(url, uow -> {
            LogRepository logs = new LogRepository(uow.entityManager());
            logs.markAired("KWLO", MONDAY);
            ClockGrid grid = Houses.gridFor(uow, "KWLO", Optional.of(MONDAY));
            board.set(logs.board(grid, MONDAY));
        });
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        for (Timed spot : board.get().placements()) {
            Placement placement = spot.placement();
            lines.add("2025-08-04," + spot.at() + "," + placement.cart() + ",a title,30.0,played," + placement.order()
                    + "-" + placement.line());
        }
        Path path = tmp.resolve("KWLO250804.ASR");
        Files.writeString(path, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);

        Shell.Result result = cartwall("post-asrun", "KWLO", "2025-08-04", path.toString());
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("aired    1", "missed   0");
    }

    @Test
    void postingAgainstADayThatHasNotAiredIsRefused() throws IOException {
        anOrder();
        cartwall("build", "2025-08-04");
        Path path = tmp.resolve("empty.asr");
        Files.writeString(path, HEADER + "\n", StandardCharsets.UTF_8);
        Shell.Result result = cartwall("post-asrun", "KWLO", "2025-08-04", path.toString());
        assertThat(result.code()).isEqualTo(REFUSED);
        assertThat(result.output()).contains("not_aired");
    }

    @Test
    void postingAFileThatIsNotThere() {
        assertThat(cartwall("post-asrun", "KWLO", "2025-08-04", "nowhere.asr").code())
                .isNotZero();
    }
}
