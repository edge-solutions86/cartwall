package io.github.rkaushikdev.cartwall.services;

import static io.github.rkaushikdev.cartwall.services.Numbering.CEILING;
import static io.github.rkaushikdev.cartwall.services.Numbering.FIRST;
import static io.github.rkaushikdev.cartwall.services.Numbering.parseNumber;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.cartwall.persistence.TestDatabase;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.persistence.models.CounterRow;
import io.github.rkaushikdev.cartwall.services.Numbering.DocumentNumber;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NumberingTest {

    private UnitOfWork session;
    private Numbering numbers;

    @BeforeEach
    void openASession() {
        session = TestDatabase.fresh().unitOfWork();
        numbers = new Numbering(session.entityManager());
    }

    @AfterEach
    void closeTheSession() {
        session.close();
    }

    @Test
    void theFirstNumberOfAYearIsOnePastTheFloor() {
        assertThat(numbers.next("ORD", 2025)).isEqualTo("25-1001");
    }

    @Test
    void numbersRunOnWithoutGaps() {
        List<String> issued =
                IntStream.range(0, 5).mapToObj(n -> numbers.next("ORD", 2025)).toList();
        assertThat(issued).containsExactly("25-1001", "25-1002", "25-1003", "25-1004", "25-1005");
    }

    @Test
    void twoSeriesDoNotShareACounter() {
        assertThat(numbers.next("ORD", 2025)).isEqualTo("25-1001");
        assertThat(numbers.next("INV", 2025)).isEqualTo("25-1001");
        assertThat(numbers.next("ORD", 2025)).isEqualTo("25-1002");
        assertThat(numbers.next("INV", 2025)).isEqualTo("25-1002");
    }

    @Test
    void aNewYearStartsTheSeriesAgain() {
        for (int n = 0; n < 3; n++) {
            numbers.next("ORD", 2025);
        }
        assertThat(numbers.next("ORD", 2026)).isEqualTo("26-1001");
        assertThat(numbers.next("ORD", 2025)).isEqualTo("25-1004");
    }

    @Test
    void theCounterCanBeReadWithoutTakingANumber() {
        assertThat(numbers.peek("ORD", 2025)).isEqualTo(FIRST);
        numbers.next("ORD", 2025);
        assertThat(numbers.peek("ORD", 2025)).isEqualTo(FIRST + 1);
        assertThat(numbers.peek("ORD", 2025)).isEqualTo(FIRST + 1);
    }

    @Test
    void aSeriesThatCannotBeDrawnFrom() {
        assertThatThrownBy(() -> numbers.next("", 2025))
                .isInstanceOf(NumberingError.class)
                .hasMessageContaining("needs a code");
        assertThatThrownBy(() -> numbers.next("ORD", 1999))
                .isInstanceOf(NumberingError.class)
                .hasMessageContaining("not a year this house trades in");
        assertThatThrownBy(() -> numbers.next("ORD", 3000))
                .isInstanceOf(NumberingError.class)
                .hasMessageContaining("not a year this house trades in");
    }

    @Test
    void aBlockCanBeReservedForARun() {
        List<String> block = numbers.reserve("INV", 2025, 4);
        assertThat(block).containsExactly("25-1001", "25-1002", "25-1003", "25-1004");
        assertThat(numbers.next("INV", 2025)).isEqualTo("25-1005");
    }

    @Test
    void aBlockOfNothingIsNotABlock() {
        assertThatThrownBy(() -> numbers.reserve("INV", 2025, 0))
                .isInstanceOf(NumberingError.class)
                .hasMessageContaining("not a number of documents");
    }

    @Test
    void aSeriesThatRunsOutSaysHowManyAreLeft() {
        numbers.openAt("INV", 2025, CEILING - 2);
        assertThat(numbers.reserve("INV", 2025, 2)).containsExactly("25-9998", "25-9999");
        SeriesExhausted caught = catchThrowableOfType(SeriesExhausted.class, () -> numbers.next("INV", 2025));
        assertThat(caught).hasMessageContaining("every number in 2025");
        assertThat(caught.code()).isEqualTo("series_exhausted");
    }

    @Test
    void aBlockBiggerThanWhatIsLeftIsRefusedBeforeItStarts() {
        numbers.openAt("INV", 2025, CEILING - 3);
        SeriesExhausted caught = catchThrowableOfType(SeriesExhausted.class, () -> numbers.reserve("INV", 2025, 4));
        assertThat(caught).hasMessageContaining("has 3 numbers left");
        assertThat(caught.context()).containsEntry("left", 3);
        // And nothing was taken, so the three that are left are still there.
        assertThat(numbers.reserve("INV", 2025, 3)).containsExactly("25-9997", "25-9998", "25-9999");
    }

    @Test
    void aSeriesCanBeOpenedSomewhereElseBeforeItIsUsed() {
        numbers.openAt("ORD", 2025, 4200);
        assertThat(numbers.next("ORD", 2025)).isEqualTo("25-4201");
    }

    @Test
    void aSeriesThatHasAlreadyIssuedCannotBeReopened() {
        numbers.next("ORD", 2025);
        assertThatThrownBy(() -> numbers.openAt("ORD", 2025, 4200))
                .isInstanceOf(NumberingError.class)
                .hasMessageContaining("already issued numbers");
    }

    @Test
    void aSeriesCannotOpenBelowTheFloor() {
        assertThatThrownBy(() -> numbers.openAt("ORD", 2025, 500))
                .isInstanceOf(NumberingError.class)
                .hasMessageContaining("cannot open below 1000");
    }

    @Test
    void rollingBackPutsTheNumberBack() {
        assertThat(numbers.next("ORD", 2025)).isEqualTo("25-1001");
        session.commit();
        assertThat(numbers.next("ORD", 2025)).isEqualTo("25-1002");
        session.rollback();
        assertThat(new Numbering(session.entityManager()).next("ORD", 2025)).isEqualTo("25-1002");
    }

    @Test
    void aCommittedNumberStaysTaken() {
        new Numbering(session.entityManager()).next("ORD", 2025);
        session.commit();
        assertThat(new Numbering(session.entityManager()).peek("ORD", 2025)).isEqualTo(1001);
    }

    @Test
    void aNumberReadsBackAsAYearAndASequence() {
        assertThat(parseNumber("25-1044")).isEqualTo(new DocumentNumber(2025, 1044));
        assertThat(parseNumber("26-1001")).isEqualTo(new DocumentNumber(2026, 1001));
    }

    @Test
    void somethingThatIsNotADocumentNumber() {
        for (String text : List.of("", "1044", "25-", "-1044", "AB-1044", "25-10x4")) {
            assertThatThrownBy(() -> parseNumber(text))
                    .as(text)
                    .isInstanceOf(NumberingError.class)
                    .hasMessageContaining("not a document number");
        }
    }

    @Test
    void theCounterRowPrintsWhereItStands() {
        new Numbering(session.entityManager()).next("ORD", 2025);
        CounterRow row = session.entityManager().find(CounterRow.class, CounterRow.find("ORD", 2025));
        assertThat(row).hasToString("<CounterRow ORD 2025 at 1001>");
    }
}
