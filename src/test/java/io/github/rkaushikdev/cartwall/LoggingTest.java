package io.github.rkaushikdev.cartwall;

import static io.github.rkaushikdev.cartwall.HouseLog.Field.field;
import static io.github.rkaushikdev.cartwall.HouseLog.detail;
import static io.github.rkaushikdev.cartwall.HouseLog.event;
import static io.github.rkaushikdev.cartwall.HouseLog.failed;
import static io.github.rkaushikdev.cartwall.HouseLog.line;
import static io.github.rkaushikdev.cartwall.HouseLog.pairs;
import static io.github.rkaushikdev.cartwall.HouseLog.render;
import static io.github.rkaushikdev.cartwall.HouseLog.warn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LoggingTest {

    private ByteArrayOutputStream caught;

    @BeforeEach
    void catchEverything() {
        caught = new ByteArrayOutputStream();
        HouseLog.configure("debug", caught);
    }

    @AfterEach
    void putItBack() {
        HouseLog.configure("info", new ByteArrayOutputStream());
    }

    static List<String> written(ByteArrayOutputStream stream) {
        return Arrays.stream(stream.toString(StandardCharsets.UTF_8).split("\n"))
                .filter(text -> !text.isEmpty())
                .toList();
    }

    static List<String> word(ByteArrayOutputStream stream, int index) {
        return written(stream).stream().map(text -> text.split(" ")[index]).toList();
    }

    @Test
    void aValueIsWrittenSoItCannotSplitAField() {
        assertThat(render("KWLO")).isEqualTo("KWLO");
        assertThat(render("Black Hawk Bank")).isEqualTo("\"Black Hawk Bank\"");
        assertThat(render(null)).isEqualTo("-");
        assertThat(render(true)).isEqualTo("yes");
        assertThat(render(false)).isEqualTo("no");
        assertThat(render("")).isEqualTo("\"\"");
        assertThat(render(12)).isEqualTo("12");
    }

    @Test
    void aQuoteInsideAValueDoesNotCloseTheQuoting() {
        assertThat(render("Casey's \"General\" Store")).isEqualTo("\"Casey's 'General' Store\"");
    }

    @Test
    void pairsKeepTheOrderTheyWerePassed() {
        assertThat(pairs(field("station", "KWLO"), field("day", "2025-08-04")))
                .isEqualTo("station=KWLO day=2025-08-04");
        assertThat(pairs()).isEmpty();
    }

    @Test
    void aLineIsAStampALevelAnEventAndThePairs() {
        String text = line("info", "log.released", field("station", "KWLO"), field("rev", 0));
        String[] words = text.split(" ");
        assertThat(words[1]).isEqualTo("info");
        assertThat(words[2]).isEqualTo("log.released");
        assertThat(Arrays.copyOfRange(words, 3, words.length)).containsExactly("station=KWLO", "rev=0");
        assertThat(words[0].chars().filter(c -> c == ':').count()).isEqualTo(2);
    }

    @Test
    void anEventWithNoFieldsIsStillALine() {
        assertThat(line("info", "run.started")).endsWith("run.started");
    }

    @Test
    void anEventLandsOnTheStream() {
        event("log.released", field("station", "KWLO"), field("day", "2025-08-04"), field("rev", 0));
        assertThat(written(caught)).hasSize(1);
        assertThat(caught.toString(StandardCharsets.UTF_8)).contains("log.released station=KWLO day=2025-08-04 rev=0");
    }

    @Test
    void theLevelsAllWrite() {
        detail("a.debug");
        event("a.info");
        warn("a.warning");
        failed("a.error");
        assertThat(word(caught, 1)).containsExactly("debug", "info", "warning", "error");
    }

    @Test
    void theLevelHidesWhatIsBelowIt() {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        HouseLog.configure("warning", stream);
        detail("a.debug");
        event("a.info");
        warn("a.warning");
        assertThat(word(stream, 2)).containsExactly("a.warning");
    }

    @Test
    void theLevelComesFromTheEnvironmentWhenNobodySays() {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        Map<String, String> environment = Map.of(HouseLog.LEVEL_VAR, "error");
        HouseLog.configure(null, stream, environment::get);
        warn("a.warning");
        failed("a.error");
        assertThat(word(stream, 2)).containsExactly("a.error");
    }

    @Test
    void aLevelThatIsNotALevel() {
        assertThatThrownBy(() -> HouseLog.configure("chatty", new ByteArrayOutputStream()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a log level");
    }

    @Test
    void configuringTwiceDoesNotDoubleEveryLine() {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        HouseLog.configure("info", stream);
        HouseLog.configure("info", stream);
        event("a.info");
        assertThat(written(stream)).hasSize(1);
    }

    @Test
    void thePackageLoggerDoesNotLeakIntoTheRootLogger() {
        HouseLog.configure("info", new ByteArrayOutputStream());
        assertThat(HouseLog.propagates()).isFalse();
    }

    @Test
    void aTracebackIsFlattenedOntoTheOneLine() {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        HouseLog.configure("info", stream);
        try {
            throw new IllegalStateException("the break is full");
        } catch (IllegalStateException failure) {
            HouseLog.logger().atError().setCause(failure).addKeyValue("hour", 7).log("a.failed");
        }
        List<String> lines = written(stream);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("hour=7").contains("the break is full");
    }

    @Test
    void theCommandLineCanTurnItOffForABlock() {
        HouseLog.quietly(() -> event("a.hidden"));
        event("a.shown");
        assertThat(word(caught, 2)).containsExactly("a.shown");
    }

    @Test
    void turningItOffPutsItBackTheWayItWas() {
        assertThat(HouseLog.isQuiet()).isFalse();
        HouseLog.quietly(() -> {
            assertThat(HouseLog.isQuiet()).isTrue();
        });
        assertThat(HouseLog.isQuiet()).isFalse();
    }

    @Test
    void aNamedLoggerSitsUnderThePackageOne() {
        assertThat(HouseLog.logger("placer").getName()).isEqualTo("cartwall.placer");
        assertThat(HouseLog.logger().getName()).isEqualTo("cartwall");
    }

    @Test
    void theFormatterWorksOnARecordNobodyGaveFieldsTo() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        LoggingEvent record = new LoggingEvent(
                LoggingTest.class.getName(), context.getLogger("cartwall"), Level.INFO, "a.info", null, null);
        assertThat(new HouseLayout().doLayout(record).strip()).endsWith("a.info");
    }
}
