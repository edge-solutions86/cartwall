package io.github.rkaushikdev.cartwall.api.routers;

import static io.github.rkaushikdev.cartwall.HouseLog.Field.field;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.github.rkaushikdev.cartwall.ConflictError;
import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.api.Amounts;
import io.github.rkaushikdev.cartwall.api.Amounts.LengthJson;
import io.github.rkaushikdev.cartwall.api.Caller;
import io.github.rkaushikdev.cartwall.api.Requires;
import io.github.rkaushikdev.cartwall.api.Role;
import io.github.rkaushikdev.cartwall.api.Work;
import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Advertiser;
import io.github.rkaushikdev.cartwall.domain.Agency;
import io.github.rkaushikdev.cartwall.domain.Category;
import io.github.rkaushikdev.cartwall.domain.Credit;
import io.github.rkaushikdev.cartwall.domain.Cut;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.services.Audit;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Advertisers, agencies, conflict codes and the copy library.
 *
 * <p>The read everybody wants is the awkward one: {@code GET /accounts/DEALF/conflicts}, who this
 * advertiser may not sit beside. Order entry asks it before quoting a fixed position and the
 * traffic director asks it when a schedule will not place, and working it out means walking every
 * account on file, which is why it is a route and not something a client assembles.
 *
 * <p>Filing copy belongs to production rather than to sales, so it sits behind traffic. A seller
 * who could file copy could name a cart nobody has dubbed, and the log would go out with a hole.
 */
@RestController
@RequestMapping("/accounts")
@Tag(name = "accounts")
public class AccountsController {

    private static final String REQUIRED = "Field required";

    private final Work work;

    public AccountsController(Work work) {
        this.work = work;
    }

    /** An account to open. */
    public record AdvertiserIn(
            @NotNull(message = REQUIRED) String code,
            @NotNull(message = REQUIRED) String name,
            List<String> categories,
            String agency,
            String parent,
            Credit credit) {

        public AdvertiserIn {
            categories = categories == null ? List.of() : List.copyOf(categories);
            credit = credit == null ? Credit.OPEN : credit;
        }
    }

    /**
     * An account as it reads back.
     *
     * @param commissionRate what comes off this account's gross; zero for direct business
     * @param payer whose name the invoice carries
     */
    public record AdvertiserOut(
            String code,
            String name,
            List<String> categories,
            Optional<String> agency,
            Optional<String> parent,
            Credit credit,
            String commissionRate,
            String payer) {}

    /** An agency and what it keeps. */
    public record AgencyOut(String code, String name, String rate, boolean advertiserLiable) {}

    /**
     * Somebody this account may not sit beside.
     *
     * @param through the codes the two accounts collide through, broadest first
     */
    public record ConflictOut(String code, String name, List<String> through) {}

    /**
     * A dubbed cut to file.
     *
     * @param actualTenths what it really runs, in tenths; production measures it
     */
    public record CutIn(
            @NotNull(message = REQUIRED) String cart,
            @NotNull(message = REQUIRED) String isci,
            @NotNull(message = REQUIRED) String advertiser,
            @NotNull(message = REQUIRED) @JsonDeserialize(using = LengthJson.class) String length,
            @NotNull(message = REQUIRED) Integer actualTenths,
            @NotNull(message = REQUIRED) LocalDate firstAir,
            LocalDate lastAir) {}

    /** A cut as the library holds it. */
    public record CutOut(
            String cart,
            String isci,
            String advertiser,
            String length,
            int actualTenths,
            LocalDate firstAir,
            Optional<LocalDate> lastAir,
            boolean killed) {}

    @GetMapping("/{code}")
    @Requires(Role.READER)
    public AdvertiserOut readAdvertiser(@PathVariable String code) {
        return work.apply(uow -> advertiserOut(uow, uow.accounts().book().advertiser(code)));
    }

    /** Open an account. Setting up who may be billed is a manager's job. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Requires(Role.MANAGER)
    public AdvertiserOut writeAdvertiser(@Valid @RequestBody AdvertiserIn body, Caller caller) {
        return work.apply(uow -> {
            AccountBook book = uow.accounts().book();
            if (book.advertisers().containsKey(body.code())) {
                throw new ConflictError(
                        "advertiser " + body.code() + " is already on file", Details.of("code", body.code()));
            }
            book.addAdvertiser(Advertiser.build(body.code(), body.name(), String.join(",", body.categories()))
                    .withAgency(body.agency())
                    .withParent(body.parent())
                    .withCredit(body.credit()));
            uow.accounts().saveBook(book);
            Audit.tryRecord(
                    uow.entityManager(),
                    "advertiser.opened",
                    caller.name(),
                    "advertiser",
                    body.code(),
                    field("name", body.name()),
                    field("agency", body.agency()));
            return advertiserOut(uow, book.advertiser(body.code()));
        });
    }

    @GetMapping
    @Requires(Role.READER)
    public List<AgencyOut> listAgencies() {
        return work.apply(uow -> uow.accounts().book().agencies().values().stream()
                .sorted(Comparator.comparing(Agency::code))
                .map(agency ->
                        new AgencyOut(agency.code(), agency.name(), rate(agency.rate()), agency.advertiserLiable()))
                .toList());
    }

    /** Everybody on file this advertiser may not sit beside, in code order. */
    @GetMapping("/{code}/conflicts")
    @Requires(Role.READER)
    public List<ConflictOut> readConflicts(@PathVariable String code) {
        return work.apply(uow -> {
            AccountBook book = uow.accounts().book();
            Advertiser subject = book.advertiser(code);
            return book.conflicting(code).stream()
                    .map(other -> new ConflictOut(
                            other.code(),
                            other.name(),
                            subject.sharedCategories(other).stream()
                                    .map(Category::toString)
                                    .sorted()
                                    .toList()))
                    .toList();
        });
    }

    /** Put an account on hold, or take it off. A manager's decision. */
    @PutMapping("/{code}/credit")
    @Requires(Role.MANAGER)
    public AdvertiserOut setCredit(
            @PathVariable String code,
            @Parameter(description = "open, cash in advance, or hold") @RequestParam Credit standing,
            Caller caller) {
        return work.apply(uow -> {
            AccountBook book = uow.accounts().book();
            Advertiser was = book.advertiser(code);
            book.replaceAdvertiser(was.withCredit(standing));
            uow.accounts().saveBook(book);
            Audit.tryRecord(
                    uow.entityManager(),
                    "advertiser.credit",
                    caller.name(),
                    "advertiser",
                    code,
                    field("was", was.credit().value()),
                    field("now", standing.value()));
            return advertiserOut(uow, book.advertiser(code));
        });
    }

    /** File a dubbed cut. Production's job, so it sits behind traffic. */
    @PostMapping("/copy")
    @ResponseStatus(HttpStatus.CREATED)
    @Requires(Role.TRAFFIC)
    public CutOut fileCopy(@Valid @RequestBody CutIn body, Caller caller) {
        return work.apply(uow -> {
            Cut cut = new Cut(
                    body.cart(),
                    body.isci(),
                    body.advertiser(),
                    Amounts.lengthOf(body.length()),
                    new Duration(body.actualTenths()),
                    body.firstAir(),
                    Optional.ofNullable(body.lastAir()),
                    false);
            uow.accounts().fileCut(cut);
            Audit.tryRecord(
                    uow.entityManager(),
                    "copy.filed",
                    caller.name(),
                    "cart",
                    cut.cart(),
                    field("advertiser", cut.advertiser()),
                    field("length", cut.length().toString()),
                    field("runs", cut.actual().toString()));
            return cutOut(cut);
        });
    }

    @GetMapping("/{code}/copy")
    @Requires(Role.READER)
    public List<CutOut> listCopy(@PathVariable String code) {
        return work.apply(uow -> uow.accounts().library(code).forAdvertiser(code).stream()
                .map(AccountsController::cutOut)
                .toList());
    }

    private static AdvertiserOut advertiserOut(UnitOfWork uow, Advertiser advertiser) {
        AccountBook book = uow.accounts().book();
        return new AdvertiserOut(
                advertiser.code(),
                advertiser.name(),
                advertiser.categories().stream()
                        .map(Category::toString)
                        .sorted()
                        .toList(),
                advertiser.agency(),
                advertiser.parent(),
                advertiser.credit(),
                rate(book.commissionRate(advertiser.code())),
                book.liableParty(advertiser.code()));
    }

    private static CutOut cutOut(Cut cut) {
        return new CutOut(
                cut.cart(),
                cut.isci(),
                cut.advertiser(),
                cut.length().toString(),
                cut.actual().tenths(),
                cut.firstAir(),
                cut.lastAir(),
                cut.killed());
    }

    /** A rate with no trailing zeros: {@code 0.1500} off a NUMERIC(5,4) column reads {@code 0.15}. */
    static String rate(BigDecimal rate) {
        return rate.stripTrailingZeros().toPlainString();
    }
}
