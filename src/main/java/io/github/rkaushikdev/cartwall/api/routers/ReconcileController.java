package io.github.rkaushikdev.cartwall.api.routers;

import static io.github.rkaushikdev.cartwall.HouseLog.Field.field;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.LookupFailure;
import io.github.rkaushikdev.cartwall.api.Caller;
import io.github.rkaushikdev.cartwall.api.Requires;
import io.github.rkaushikdev.cartwall.api.Role;
import io.github.rkaushikdev.cartwall.api.Work;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.logs.DayLog;
import io.github.rkaushikdev.cartwall.logs.LogStatus;
import io.github.rkaushikdev.cartwall.persistence.LogRepository;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.persistence.models.SpotRow;
import io.github.rkaushikdev.cartwall.playout.AsRunError;
import io.github.rkaushikdev.cartwall.playout.AsRunFile;
import io.github.rkaushikdev.cartwall.playout.Played;
import io.github.rkaushikdev.cartwall.reconcile.Match;
import io.github.rkaushikdev.cartwall.reconcile.Matcher;
import io.github.rkaushikdev.cartwall.reconcile.Posting;
import io.github.rkaushikdev.cartwall.reconcile.Reconciliation;
import io.github.rkaushikdev.cartwall.services.Audit;
import io.github.rkaushikdev.cartwall.services.Houses;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Posting the as-run, and reading back what the comparison found.
 *
 * <p>The as-run arrives as the automation wrote it, a CSV file, so these routes take a body of text
 * rather than JSON: whoever runs the nightly job should not have to escape a few thousand lines of
 * CSV at two in the morning.
 *
 * <p>Two routes run the same comparison and only one writes. {@code .../check} compares and
 * answers; {@code POST /reconcile/{station}/{day}} compares and writes the result onto the stored
 * spots so billing can see it. The dry run matters most on the day the automation was upgraded and
 * quietly stopped echoing references back, because then every match comes off the cart pass and
 * the drift column is the only sign anything is wrong. The read route answers off what was already
 * posted, because by the time somebody asks why a line is short the file is a week old.
 */
@RestController
@RequestMapping("/reconcile")
@Tag(name = "reconcile")
public class ReconcileController {

    private final Work work;

    public ReconcileController(Work work) {
        this.work = work;
    }

    /**
     * A scheduled spot the as-run answered.
     *
     * @param driftSeconds how far it moved; zero means it ran where it was sold
     * @param byReference true when the automation echoed the order and line back
     */
    public record MatchOut(
            String order,
            int line,
            String cart,
            String scheduledAt,
            String airedAt,
            int driftSeconds,
            boolean onTime,
            @JsonProperty("short") boolean isShort,
            Played status,
            boolean byReference) {}

    /** A scheduled spot nothing in the as-run answered, and why. */
    public record MissedOut(String order, int line, String cart, String scheduledAt, String reason) {}

    /** An as-run line that answered to nothing on the log. */
    public record ExtraOut(String cart, String title, String airedAt, Played status) {}

    /** How many spots one line is short by. */
    public record OwedOut(String order, int line, int spots) {}

    /**
     * The comparison, written out.
     *
     * @param owed what each line is short by, worst first
     */
    public record CheckOut(
            String station,
            LocalDate day,
            int scheduled,
            int aired,
            boolean clean,
            List<MatchOut> matched,
            List<MissedOut> missed,
            List<ExtraOut> extra,
            List<OwedOut> owed) {}

    /**
     * What posting changed.
     *
     * @param unlogged as-run lines that answered to nothing on the log
     */
    public record PostOut(String station, LocalDate day, int aired, int missed, int moved, int unlogged) {}

    /** One stored spot and what the as-run said about it. */
    public record SpotOut(
            String order,
            int line,
            String advertiser,
            String cart,
            int hour,
            String scheduledAt,
            String airedAt,
            Played status) {}

    /** Compare the file against the log and answer. Nothing is written. */
    @PostMapping("/{station}/{day}/check")
    @Requires(Role.TRAFFIC)
    public CheckOut check(
            @PathVariable String station, @PathVariable LocalDate day, @RequestBody(required = false) byte[] body) {
        return work.apply(uow -> checkOut(compare(uow, station, day, text(body))));
    }

    /** Compare, then write the result onto the stored spots. */
    @PostMapping("/{station}/{day}")
    @Requires(Role.TRAFFIC)
    public PostOut postAsRun(
            @PathVariable String station,
            @PathVariable LocalDate day,
            @RequestBody(required = false) byte[] body,
            @Parameter(description = "Post against a day that has not aired") @RequestParam(defaultValue = "false")
                    boolean force,
            Caller caller) {
        return work.apply(uow -> {
            Reconciliation result = compare(uow, station, day, text(body));
            Posting report = Posting.post(uow.entityManager(), result, force);
            Audit.tryRecord(
                    uow.entityManager(),
                    "asrun.posted",
                    caller.name(),
                    "log",
                    result.station() + " " + day,
                    field("aired", report.aired()),
                    field("missed", report.missed()),
                    field("moved", report.moved()),
                    field("forced", force));
            return new PostOut(
                    report.station(),
                    report.day(),
                    report.aired(),
                    report.missed(),
                    report.moved(),
                    report.unmatched());
        });
    }

    /** Say the day went out. Until it has, an as-run against it is refused. */
    @PostMapping("/{station}/{day}/aired")
    @Requires(Role.MANAGER)
    public PostOut markAired(@PathVariable String station, @PathVariable LocalDate day, Caller caller) {
        return work.apply(uow -> {
            ClockGrid grid = Houses.gridFor(uow, station, Optional.of(day));
            LogStatus status = new LogRepository(uow.entityManager()).markAired(grid.station(), day);
            Audit.tryRecord(
                    uow.entityManager(),
                    "log.aired",
                    caller.name(),
                    "log",
                    grid.station() + " " + day,
                    field("revision", status.revision()));
            return new PostOut(grid.station(), day, 0, 0, 0, 0);
        });
    }

    /** What one order actually did over a span, off what was posted. */
    @GetMapping("/order/{order}")
    @Requires(Role.READER)
    public List<SpotOut> readOrder(
            @PathVariable String order,
            @Parameter(description = "First day of the span") @RequestParam LocalDate first,
            @Parameter(description = "Last day of the span") @RequestParam LocalDate last,
            @Parameter(description = "Aired spots, or the ones that were missed") @RequestParam(defaultValue = "true")
                    boolean aired) {
        return work.apply(uow -> {
            if (last.isBefore(first)) {
                throw new LookupFailure(
                        last + " is before " + first, Details.of("first", first.toString(), "last", last.toString()));
            }
            List<SpotRow> found = aired
                    ? Posting.airedSpots(uow.entityManager(), order, first, last)
                    : Posting.missedSpots(uow.entityManager(), order, first, last);
            return found.stream().map(ReconcileController::spotOut).toList();
        });
    }

    /** The raw request body as UTF-8 text, refused when it is not. */
    static String text(byte[] body) {
        if (body == null) {
            return "";
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException notText) {
            throw new AsRunError("the as-run is not utf-8 text");
        }
    }

    /** The shared half: read the log, read the file, put them side by side. */
    private static Reconciliation compare(UnitOfWork uow, String station, LocalDate day, String text) {
        ClockGrid grid = Houses.gridFor(uow, station, Optional.of(day));
        LogRepository logs = new LogRepository(uow.entityManager());
        if (!logs.exists(grid.station(), day)) {
            throw new LookupFailure(
                    grid.station() + " has no log for " + day,
                    Details.of("station", grid.station(), "day", day.toString()));
        }
        DayLog built = DayLog.build(grid, logs.board(grid, day));
        return Matcher.reconcile(built, AsRunFile.parse(text));
    }

    private static CheckOut checkOut(Reconciliation result) {
        return new CheckOut(
                result.station(),
                result.day(),
                result.scheduled(),
                result.aired(),
                result.isClean(),
                result.matched().stream().map(ReconcileController::matchOut).toList(),
                result.missed().stream()
                        .map(miss -> new MissedOut(
                                miss.scheduled().order(),
                                miss.scheduled().line(),
                                miss.scheduled().cart(),
                                miss.scheduled().at().twelveHour(),
                                miss.reason()))
                        .toList(),
                result.extra().stream()
                        .map(extra -> new ExtraOut(
                                extra.aired().cart(),
                                extra.aired().title(),
                                extra.aired().at().twelveHour(),
                                extra.aired().status()))
                        .toList(),
                result.owed().entrySet().stream()
                        .map(entry -> new OwedOut(
                                entry.getKey().order(), entry.getKey().line(), entry.getValue()))
                        .toList());
    }

    private static MatchOut matchOut(Match match) {
        return new MatchOut(
                match.scheduled().order(),
                match.scheduled().line(),
                match.aired().cart(),
                match.scheduled().at().twelveHour(),
                match.aired().at().twelveHour(),
                Math.floorDiv(match.drift().tenths(), 10),
                match.onTime(),
                match.isShort(),
                match.aired().status(),
                match.byReference());
    }

    private static SpotOut spotOut(SpotRow spot) {
        return new SpotOut(
                spot.getOrderNumber(),
                spot.getLine(),
                spot.getAdvertiser(),
                spot.getCart(),
                spot.getHour(),
                spot.getAt().twelveHour(),
                spot.getAiredAt() == null ? "" : spot.getAiredAt().twelveHour(),
                spot.getAiredStatus());
    }
}
