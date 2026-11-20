package io.github.rkaushikdev.cartwall.services;

import static io.github.rkaushikdev.cartwall.Fixtures.CARD_DATE;
import static io.github.rkaushikdev.cartwall.Fixtures.aGrid;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;

import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.HouseLog;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.logs.DayLog;
import io.github.rkaushikdev.cartwall.persistence.Database;
import io.github.rkaushikdev.cartwall.persistence.LogRepository;
import io.github.rkaushikdev.cartwall.persistence.TestDatabase;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.playout.AsRun;
import io.github.rkaushikdev.cartwall.playout.AsRunFile;
import io.github.rkaushikdev.cartwall.reconcile.Matcher;
import io.github.rkaushikdev.cartwall.reconcile.Posting;
import io.github.rkaushikdev.cartwall.scheduling.DayBoard;
import io.github.rkaushikdev.cartwall.scheduling.Placement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Days built, signed, aired and posted, for the tests of what is owed afterwards.
 */
final class AiredDays {

    static final String HEADER = "date,time,cart,title,length,status,reference";
    static final LocalDateTime SIGNED = LocalDateTime.of(2025, 8, 1, 16, 30);

    private AiredDays() {}

    /** A database with the cluster, the book, KWLO's card and KWLO's programming on file. */
    static Database aStationOnFile() {
        Database engine = TestDatabase.fresh();
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.stations().saveCluster(Fixtures.cluster());
            uow.accounts().saveBook(Fixtures.accounts());
            uow.cards().file(Fixtures.cards().inForce("KWLO", CARD_DATE));
            uow.stations().saveGrid(Fixtures.programming().get("KWLO"));
            uow.commit();
        }
        return engine;
    }

    /** One spot on the log: which break, and whose. */
    record Spot(int hour, int number, String order, int line) {}

    /** Build the day, and unless {@code air} is false, walk it to aired and post the as-run. */
    static void aDay(UnitOfWork uow, List<Spot> spots, List<String> asRunLines, LocalDate day, boolean air) {
        ClockGrid grid = aGrid("KWLO");
        DayBoard board = DayBoard.build(grid, day);
        for (Spot spot : spots) {
            board.avail(spot.hour(), spot.number())
                    .add(new Placement(spot.order(), spot.line(), "HYVEE", THIRTY, ClassOfTime.FIXED, "C1204"));
        }
        LogRepository logs = new LogRepository(uow.entityManager());
        logs.save(board);
        if (!air) {
            return;
        }
        logs.approve("KWLO", day, "T. Ronan", SIGNED);
        logs.release("KWLO", day, SIGNED);
        logs.markAired("KWLO", day);
        List<AsRun> rows = asRunLines.isEmpty() ? List.of() : asRun(asRunLines);
        HouseLog.quietly(() -> Posting.post(uow.entityManager(), Matcher.reconcile(DayLog.build(grid, board), rows)));
    }

    static String ran(String at, String status, String reference) {
        return "2025-08-04," + at + ",C1204,a title,30.0," + status + "," + reference;
    }

    static String ran(String at) {
        return ran(at, "played", "25-1044-1");
    }

    private static List<AsRun> asRun(List<String> lines) {
        List<String> all = new ArrayList<>();
        all.add(HEADER);
        all.addAll(lines);
        return AsRunFile.parse(String.join("\n", all) + "\n");
    }
}
