package io.github.rkaushikdev.cartwall.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class StationTest {

    static Station.Builder fm(String call, String frequency) {
        return Station.builder(call, Band.FM, new BigDecimal(frequency), "Waterloo", Service.MUSIC);
    }

    static Station.Builder fm(String call) {
        return fm(call, "99.1");
    }

    static Station.Builder am(String call, String frequency) {
        return Station.builder(call, Band.AM, new BigDecimal(frequency), "Waterloo", Service.NEWS_TALK);
    }

    static Station translator(String call, String frequency, String city, String carries) {
        return Station.builder(call, Band.TRANSLATOR, new BigDecimal(frequency), city, Service.MUSIC)
                .carries(carries)
                .build();
    }

    static Cluster cluster() {
        return new Cluster(
                "Waterloo Cedar Falls",
                List.of(
                        fm("KWLO", "99.1").build(),
                        Station.builder("KOEL-FM", Band.FM, new BigDecimal("92.3"), "Oelwein", Service.MUSIC)
                                .build(),
                        Station.builder("KCRR", Band.FM, new BigDecimal("97.7"), "Waterloo", Service.SPORTS)
                                .build(),
                        am("KXEL", "1540").daytimer(true).build(),
                        Station.builder("KCNZ", Band.AM, new BigDecimal("1250"), "Waterloo", Service.SPORTS)
                                .build(),
                        translator("KWLO-LP", "103.5", "Cedar Falls", "KWLO")));
    }

    @Test
    void aCallSignIsKOrWAndThreeOrFourLetters() {
        assertThat(fm("KWLO").build().call()).isEqualTo("KWLO");
        assertThat(fm("KOEL-FM").build().call()).isEqualTo("KOEL-FM");
        for (String bad : List.of("BWLO", "KW", "KWLOXX", "kwlo", "KWLO-HD2", "")) {
            assertThatThrownBy(() -> fm(bad).build())
                    .isInstanceOf(StationError.class)
                    .hasMessageContaining("not a call sign");
        }
    }

    @Test
    void fmChannelsAreOddTenthsBetween881And1079() {
        assertThat(fm("KWLO", "88.1").build()).isNotNull();
        assertThat(fm("KWLO", "107.9").build()).isNotNull();
        for (String bad : List.of("88.2", "99.2", "107.8")) {
            assertThatThrownBy(() -> fm("KWLO", bad).build())
                    .isInstanceOf(StationError.class)
                    .hasMessageContaining("tenths run odd");
        }
        for (String bad : List.of("87.9", "108.1")) {
            assertThatThrownBy(() -> fm("KWLO", bad).build())
                    .isInstanceOf(StationError.class)
                    .hasMessageContaining("off the FM band");
        }
    }

    @Test
    void amChannelsAreTenKilohertzApartBetween540And1700() {
        assertThat(am("KXEL", "540").build()).isNotNull();
        assertThat(am("KXEL", "1700").build()).isNotNull();
        assertThatThrownBy(() -> am("KXEL", "1545").build())
                .isInstanceOf(StationError.class)
                .hasMessageContaining("10 kHz channel");
        assertThatThrownBy(() -> am("KXEL", "530").build())
                .isInstanceOf(StationError.class)
                .hasMessageContaining("off the AM band");
        assertThatThrownBy(() -> am("KXEL", "1540.5").build())
                .isInstanceOf(StationError.class)
                .hasMessageContaining("whole number of kilohertz");
    }

    @Test
    void onlyAnAmCanBeADaytimer() {
        assertThat(am("KXEL", "1540").daytimer(true).build().daytimer()).isTrue();
        assertThatThrownBy(() -> fm("KWLO").daytimer(true).build())
                .isInstanceOf(StationError.class)
                .hasMessageContaining("not AM");
    }

    @Test
    void aTranslatorHasToCarrySomething() {
        assertThatThrownBy(() -> Station.builder(
                                "KWLO-LP", Band.TRANSLATOR, new BigDecimal("96.5"), "Cedar Falls", Service.MUSIC)
                        .build())
                .isInstanceOf(StationError.class)
                .hasMessageContaining("nothing to carry");
        assertThatThrownBy(() -> fm("KWLO").carries("KWLO").build())
                .isInstanceOf(StationError.class)
                .hasMessageContaining("cannot carry itself");
    }

    @Test
    void theCommercialLoadHasToBeMinutesInAnHour() {
        assertThat(fm("KWLO").commercialLoad(18).build().commercialLoad()).isEqualTo(18);
        for (int bad : List.of(-1, 61)) {
            assertThatThrownBy(() -> fm("KWLO").commercialLoad(bad).build())
                    .isInstanceOf(StationError.class)
                    .hasMessageContaining("is not a load");
        }
    }

    @Test
    void howASignalReadsOnPaper() {
        Station station = Station.builder("KOEL-FM", Band.FM, new BigDecimal("92.3"), "Oelwein", Service.MUSIC)
                .build();
        assertThat(station.dial()).isEqualTo("92.3 FM");
        assertThat(station.legalId()).isEqualTo("KOEL-FM, Oelwein");
        assertThat(station).hasToString("KOEL-FM");
        assertThat(am("KXEL", "1540").build().dial()).isEqualTo("1540 AM");
    }

    @Test
    void theClusterFindsItsOwnAndRefusesTheRest() {
        Cluster cluster = cluster();
        assertThat(cluster.station("KXEL").band()).isSameAs(Band.AM);
        assertThat(cluster.has("KCRR")).isTrue();
        assertThat(cluster.has("WHO")).isFalse();
        assertThat(cluster.size()).isEqualTo(6);
        assertThat(cluster.iterator().next().call()).isEqualTo("KWLO");
        assertThatThrownBy(() -> cluster.station("WHO"))
                .isInstanceOf(StationError.class)
                .hasMessageContaining("not one of ours");
    }

    @Test
    void aCallSignCannotAppearTwice() {
        assertThatThrownBy(() -> new Cluster(
                        "Waterloo",
                        List.of(fm("KWLO").build(), fm("KWLO", "97.7").build())))
                .isInstanceOf(StationError.class)
                .hasMessageContaining("appears twice");
    }

    @Test
    void aTranslatorHasToCarrySomethingInTheSameCluster() {
        assertThatThrownBy(
                        () -> new Cluster("Waterloo", List.of(translator("KWLO-LP", "103.5", "Cedar Falls", "KWLO"))))
                .isInstanceOf(StationError.class)
                .hasMessageContaining("not in the cluster");
    }

    @Test
    void aSimulcastLoopIsCaughtWhenTheClusterIsBuilt() {
        assertThatThrownBy(() -> new Cluster(
                        "Waterloo",
                        List.of(
                                fm("KWLO", "99.1").carries("KCRR").build(),
                                fm("KCRR", "97.7").carries("KWLO").build())))
                .isInstanceOf(StationError.class)
                .hasMessageContaining("loops back");
    }

    @Test
    void onlyTheSignalsWithTheirOwnProgrammingGetALog() {
        Cluster cluster = cluster();
        assertThat(cluster.logged())
                .extracting(Station::call)
                .containsExactly("KWLO", "KOEL-FM", "KCRR", "KXEL", "KCNZ");
        assertThat(cluster.station("KWLO-LP").isSatellite()).isTrue();
        assertThat(cluster.station("KWLO").isSatellite()).isFalse();
    }

    @Test
    void aSpotBoughtOnTheTranslatorIsScheduledOnTheParent() {
        Cluster cluster = cluster();
        assertThat(cluster.loggingStation("KWLO-LP").call()).isEqualTo("KWLO");
        assertThat(cluster.loggingStation("KWLO").call()).isEqualTo("KWLO");
    }

    @Test
    void aSpotOnTheParentReachesTheTranslatorToo() {
        Cluster cluster = cluster();
        List<String> reached =
                cluster.airsOn("KWLO").stream().map(Station::call).toList();
        assertThat(reached).containsExactly("KWLO", "KWLO-LP");
        assertThat(cluster.airsOn("KWLO-LP")).extracting(Station::call).isEqualTo(reached);
        assertThat(cluster.airsOn("KCRR")).extracting(Station::call).containsExactly("KCRR");
    }

    @Test
    void aChainOfSimulcastsResolvesToTheOneLog() {
        Cluster chain = new Cluster(
                "Waterloo",
                List.of(
                        fm("KWLO", "99.1").build(),
                        translator("KWLO-LP", "103.5", "Cedar Falls", "KWLO"),
                        translator("KCFI-LP", "104.9", "Hudson", "KWLO-LP")));
        assertThat(chain.loggingStation("KCFI-LP").call()).isEqualTo("KWLO");
        assertThat(chain.airsOn("KCFI-LP")).extracting(Station::call).containsExactly("KWLO", "KWLO-LP", "KCFI-LP");
    }
}
