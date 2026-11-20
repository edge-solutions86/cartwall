package io.github.rkaushikdev.cartwall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.domain.AccountError;
import io.github.rkaushikdev.cartwall.domain.ClockError;
import io.github.rkaushikdev.cartwall.domain.DaypartError;
import io.github.rkaushikdev.cartwall.domain.RateError;
import io.github.rkaushikdev.cartwall.domain.SpotError;
import io.github.rkaushikdev.cartwall.domain.StationError;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The error tree is an interface. These tests are what keeps it one. */
class ErrorsTest {

    static List<CartwallError> leaves() {
        return List.of(
                new TimeError("x"),
                new MoneyError("x"),
                new StationError("x"),
                new DaypartError("x"),
                new ClockError("x"),
                new SpotError("x"),
                new AccountError("x"),
                new RateError("x"));
    }

    static Stream<CartwallError> leaf() {
        return leaves().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("leaf")
    void everyRefusalInThePackageHasOneRoot(CartwallError leaf) {
        assertThat(leaf).isInstanceOf(CartwallError.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("leaf")
    void everyLeafCarriesItsOwnCode(CartwallError leaf) {
        assertThat(leaf.code()).isNotEqualTo(new CartwallError("x").code());
        assertThat(leaf.code()).isNotEqualTo(new ValidationError("x").code());
    }

    @Test
    void noTwoLeavesShareACode() {
        List<String> codes = leaves().stream().map(CartwallError::code).toList();
        assertThat(Set.copyOf(codes)).hasSameSizeAs(codes);
    }

    @Test
    void theOldExceptValueErrorAroundOrderEntryStillCatchesEverything() {
        // Python made every leaf a ValueError as well. Java has one superclass, so the
        // catch-everything around order entry is a catch of ValidationError.
        for (CartwallError leaf : leaves()) {
            assertThat(leaf).isInstanceOf(ValidationError.class);
        }
    }

    @Test
    void aLookupFailureIsAKeyErrorSoDictStyleCallersStillWork() {
        // Python made LookupFailure a KeyError as well. Here a lookup failure is its own branch
        // under the root, never a validation failure, so a caller catching misses catches it.
        assertThat(new LookupFailure("nothing on file")).isNotInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> {
                    throw new LookupFailure("nothing on file");
                })
                .isInstanceOf(LookupFailure.class)
                .isInstanceOf(CartwallError.class);
    }

    @Test
    void aLookupFailurePrintsASentenceAndNotAQuotedArgument() {
        assertThat(new LookupFailure("nothing on file")).hasToString("nothing on file");
        assertThat(new LookupFailure("nothing on file").getMessage()).isEqualTo("nothing on file");
    }

    @Test
    void aConflictIsNotAValidationFailure() {
        ConflictError conflict = new ConflictError("no");
        assertThat(conflict).isNotInstanceOf(ValidationError.class);
        assertThat(conflict).isNotInstanceOf(IllegalArgumentException.class);
        assertThat(conflict).isInstanceOf(CartwallError.class);
    }

    @Test
    void anErrorCarriesTheSpecificsBesideTheProse() {
        SpotError failure =
                new SpotError("cart C1204 runs :32 against a :30", Details.of("cart", "C1204", "ran", "32.0"));
        assertThat(failure.getMessage()).isEqualTo("cart C1204 runs :32 against a :30");
        assertThat(failure.context()).isEqualTo(Map.of("cart", "C1204", "ran", "32.0"));
        assertThat(failure).hasToString("cart C1204 runs :32 against a :30");
    }

    @Test
    void anErrorHandsTheHttpLayerSomethingItCanSerialise() {
        AccountError failure = new AccountError("advertiser NOPE is not on file", Details.of("advertiser", "NOPE"));
        assertThat(failure.asMap())
                .isEqualTo(Map.of(
                        "code",
                        "bad_account",
                        "message",
                        "advertiser NOPE is not on file",
                        "context",
                        Map.of("advertiser", "NOPE")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theContextIsACopySoACallerCannotReachBackIn() {
        RateError failure = new RateError("no card", Details.of("station", "KWLO"));
        Map<String, Object> body = failure.asMap();
        ((Map<String, Object>) body.get("context")).put("station", "KCRR");
        assertThat(failure.context()).containsEntry("station", "KWLO");
    }

    @Test
    void catchingTheRootCatchesALeafRaisedAnywhere() {
        assertThatThrownBy(() -> {
                    throw new TimeError("that runs past midnight");
                })
                .isInstanceOf(CartwallError.class)
                .satisfies(caught -> assertThat(((CartwallError) caught).code()).isEqualTo("bad_time"));
    }
}
