package io.github.rkaushikdev.cartwall.cli;

import static io.github.rkaushikdev.cartwall.Fixtures.CARD_DATE;
import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.Fixtures.aGrid;
import static io.github.rkaushikdev.cartwall.cli.Main.REFUSED;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.HouseLog;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.Settings;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.Cut;
import io.github.rkaushikdev.cartwall.domain.DaypartGrid;
import io.github.rkaushikdev.cartwall.domain.Flight;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.logs.DayLog;
import io.github.rkaushikdev.cartwall.persistence.Database;
import io.github.rkaushikdev.cartwall.persistence.LogRepository;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.persistence.models.InvoiceRow;
import io.github.rkaushikdev.cartwall.playout.AsRunFile;
import io.github.rkaushikdev.cartwall.reconcile.Matcher;
import io.github.rkaushikdev.cartwall.reconcile.Posting;
import io.github.rkaushikdev.cartwall.scheduling.DayBoard;
import io.github.rkaushikdev.cartwall.scheduling.Placement;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliBillingTest {

    private static final ClassOfTime FIXED = ClassOfTime.FIXED;
    private static final LocalDate FRIDAY = LocalDate.of(2025, 8, 8);
    private static final LocalDateTime SIGNED = LocalDateTime.of(2025, 8, 1, 16, 30);
    private static final String HEADER = "date,time,cart,title,length,status,reference";

    @TempDir
    Path tmp;

    private String url;
    private final Shell shell = new Shell();

    @BeforeEach
    void putKwloOnFile() {
        url = Shell.fileUrl(tmp, "clibill");
        Shell.seed(url, uow -> {
            uow.stations().saveCluster(Fixtures.cluster());
            uow.accounts().saveBook(Fixtures.accounts());
            uow.cards().file(Fixtures.cards().inForce("KWLO", CARD_DATE));
            uow.stations().saveDayparts("KWLO", DaypartGrid.standardGrid());
            uow.stations().saveGrid(Fixtures.programming().get("KWLO"));
            uow.accounts().fileCut(new Cut("C1204", "HYVE2501", "HYVEE", THIRTY, new Duration(300), MONDAY));
        });
    }

    /** One order, one aired spot, ready for the invoice run to find. */
    private void billed(String advertiser, String agency) {
        ClockGrid grid = aGrid("KWLO");
        Shell.change(url, uow -> {
            Order order = new Order(
                    "25-1044",
                    advertiser,
                    LocalDate.of(2025, 7, 21),
                    "DHOLM",
                    List.of(new Line(
                            1,
                            "KWLO",
                            "AMD",
                            FIXED,
                            THIRTY,
                            Money.dollars(60),
                            Flight.build(MONDAY, FRIDAY, "M-F", 1),
                            List.of("C1204"))));
            uow.orders().save((agency == null ? order : order.withAgency(agency)).confirmed());
            DayBoard board = DayBoard.build(grid, MONDAY);
            board.avail(7, 2).add(new Placement("25-1044", 1, advertiser, THIRTY, FIXED, "C1204"));
            LogRepository logs = new LogRepository(uow.entityManager());
            logs.save(board);
            logs.approve("KWLO", MONDAY, "T. Ronan", SIGNED);
            logs.release("KWLO", MONDAY, SIGNED);
            logs.markAired("KWLO", MONDAY);
            String file = HEADER + "\n2025-08-04,07:10:00,C1204,t,30.0,played,25-1044-1\n";
            HouseLog.quietly(() -> Posting.post(
                    uow.entityManager(), Matcher.reconcile(DayLog.build(grid, board), AsRunFile.parse(file))));
        });
    }

    private void billed() {
        billed("HYVEE", null);
    }

    private Shell.Result cartwall(String... args) {
        String[] withDatabase = new String[args.length + 2];
        withDatabase[0] = "-d";
        withDatabase[1] = url;
        System.arraycopy(args, 0, withDatabase, 2, args.length);
        return shell.run(withDatabase);
    }

    private void invoiceAugust() {
        cartwall("invoice", "2025-08", "--on", "2025-08-25");
    }

    @Test
    void aMonthWithNothingAiredRaisesNothing() {
        Shell.Result result = cartwall("invoice", "2025-08");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("0 raised");
    }

    @Test
    void theInvoiceRunNamesWhatItRaised() {
        billed();
        Shell.Result result = cartwall("invoice", "2025-08", "--on", "2025-08-25");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("2025-08  2025-07-28 to 2025-08-24", "raised   25-1001", "1 raised");
    }

    @Test
    void aDryRunSaysWhatItWouldDoAndWritesNothing() {
        billed();
        Shell.Result result = cartwall("invoice", "2025-08", "--on", "2025-08-25", "--dry-run");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("dry run, nothing written");
        try (Database database = Database.open(Settings.defaults().withDatabase(url));
                UnitOfWork uow = database.unitOfWork()) {
            assertThat(Optional.ofNullable(uow.entityManager().find(InvoiceRow.class, "25-1001")))
                    .isEmpty();
        }
    }

    @Test
    void aDryRunDoesNotBurnTheNumberItShowed() {
        billed();
        cartwall("invoice", "2025-08", "--on", "2025-08-25", "--dry-run");
        Shell.Result result = cartwall("invoice", "2025-08", "--on", "2025-08-25");
        assertThat(result.out()).contains("raised   25-1001");
    }

    @Test
    void runningTheMonthTwiceSaysItSkipped() {
        billed();
        invoiceAugust();
        Shell.Result result = cartwall("invoice", "2025-08", "--on", "2025-08-25");
        assertThat(result.out()).contains("skipped  25-1044 (already invoiced)", "0 raised");
    }

    @Test
    void aMonthThatIsNotAMonth() {
        assertThat(cartwall("invoice", "August").code()).isNotZero();
    }

    @Test
    void writingACreditShowsWhatItDidToTheBalance() {
        billed();
        invoiceAugust();
        Shell.Result result =
                cartwall("credit", "25-1001", "20.00", "--reason", "did_not_run", "--on", "2025-08-26", "--spots", "1");
        assertThat(result.code()).isZero();
        assertThat(result.out())
                .contains(
                        "25-1001 against 25-1001 for did not run",
                        "gross      $20.00",
                        "owing      $60.00 becomes $40.00");
    }

    @Test
    void aCreditOnAnAgencyInvoiceShowsTheCommission() {
        billed("DEALF", "MOP");
        invoiceAugust();
        Shell.Result result = cartwall("credit", "25-1001", "20.00", "--reason", "rate_error");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("commission $3.00", "net        $17.00");
    }

    @Test
    void aCreditForSomeOtherReasonNeedsANote() {
        billed();
        invoiceAugust();
        Shell.Result result = cartwall("credit", "25-1001", "20.00", "--reason", "other");
        assertThat(result.code()).isEqualTo(REFUSED);
        assertThat(result.output()).contains("bad_credit", "needs a note");
    }

    @Test
    void aReasonThatIsNotOnTheListIsAMisuse() {
        assertThat(cartwall("credit", "25-1001", "20.00", "--reason", "felt like it")
                        .code())
                .isNotZero();
    }

    @Test
    void aCreditBiggerThanWhatIsLeftIsRefused() {
        billed();
        invoiceAugust();
        Shell.Result result = cartwall("credit", "25-1001", "100.00", "--reason", "did_not_run");
        assertThat(result.code()).isEqualTo(REFUSED);
        assertThat(result.output()).contains("over_credited");
    }

    @Test
    void nothingOutstandingSaysSo() {
        Shell.Result result = cartwall("owing");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("nothing outstanding");
    }

    @Test
    void whatIsOutstandingIsListedWithATotal() {
        billed();
        invoiceAugust();
        Shell.Result result = cartwall("owing");
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("25-1001", "$60.00", "1 invoices, $60.00 outstanding");
    }

    @Test
    void aFullyCreditedInvoiceDropsOffTheList() {
        billed();
        invoiceAugust();
        cartwall("credit", "25-1001", "60.00", "--reason", "did_not_run");
        assertThat(cartwall("owing").out()).contains("nothing outstanding");
    }
}
