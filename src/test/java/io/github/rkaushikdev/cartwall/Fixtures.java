package io.github.rkaushikdev.cartwall;

import static io.github.rkaushikdev.cartwall.domain.Break.local;
import static io.github.rkaushikdev.cartwall.domain.RateCard.Row.row;

import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Advertiser;
import io.github.rkaushikdev.cartwall.domain.Agency;
import io.github.rkaushikdev.cartwall.domain.Assignment;
import io.github.rkaushikdev.cartwall.domain.Band;
import io.github.rkaushikdev.cartwall.domain.Break;
import io.github.rkaushikdev.cartwall.domain.BreakKind;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.Clock;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.Cluster;
import io.github.rkaushikdev.cartwall.domain.DaypartGrid;
import io.github.rkaushikdev.cartwall.domain.House;
import io.github.rkaushikdev.cartwall.domain.RateCard;
import io.github.rkaushikdev.cartwall.domain.RateCardBook;
import io.github.rkaushikdev.cartwall.domain.SeparationBook;
import io.github.rkaushikdev.cartwall.domain.Service;
import io.github.rkaushikdev.cartwall.domain.Station;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One real cluster, filed once, so every test argues about the same numbers.
 *
 * <p>Six signals in Waterloo and Cedar Falls. The tables below are the ones the traffic director
 * would have on paper, and every expected value in the suite can be worked back to them with a
 * pencil. The dates are 2025. Broadcast August 2025 runs 7/28 to 8/24, which is why so many tests
 * pick a week in early August.
 *
 * <p>Every builder hands back a fresh object, because the books are mutable and one test filing a
 * rule must not file it for the next.
 */
public final class Fixtures {

    public static final ClassOfTime FIXED = ClassOfTime.FIXED;
    public static final ClassOfTime NOTICE = ClassOfTime.NOTICE;
    public static final ClassOfTime IMMEDIATE = ClassOfTime.IMMEDIATE;

    public static final LocalDate CARD_DATE = LocalDate.of(2025, 1, 1);

    /** A Monday, and the first day of the broadcast week 8/4 to 8/10. */
    public static final LocalDate MONDAY = LocalDate.of(2025, 8, 4);

    public static final LocalDate FRIDAY = LocalDate.of(2025, 8, 8);
    public static final LocalDate SATURDAY = LocalDate.of(2025, 8, 9);
    public static final LocalDate SUNDAY = LocalDate.of(2025, 8, 10);

    /** The stations that get a log, in cluster order. */
    public static final List<String> LOGGED = List.of("KWLO", "KOEL-FM", "KCRR", "KXEL", "KCNZ");

    private Fixtures() {}

    /** The default line, numbered. Change what the test is about and build it. */
    public static LineBuilder aLine(int number) {
        return new LineBuilder().number(number);
    }

    /** The default order, with the default line on it. */
    public static OrderBuilder anOrder() {
        return new OrderBuilder();
    }

    public static Cluster cluster() {
        return new Cluster(
                "Waterloo Cedar Falls",
                List.of(
                        Station.builder("KWLO", Band.FM, new BigDecimal("99.1"), "Waterloo", Service.MUSIC)
                                .build(),
                        Station.builder("KOEL-FM", Band.FM, new BigDecimal("92.3"), "Oelwein", Service.MUSIC)
                                .build(),
                        Station.builder("KCRR", Band.FM, new BigDecimal("97.7"), "Grundy Center", Service.SPORTS)
                                .commercialLoad(18)
                                .build(),
                        Station.builder("KXEL", Band.AM, new BigDecimal("1540"), "Waterloo", Service.NEWS_TALK)
                                .daytimer(true)
                                .commercialLoad(18)
                                .build(),
                        Station.builder("KCNZ", Band.AM, new BigDecimal("1250"), "Cedar Falls", Service.FARM)
                                .commercialLoad(18)
                                .build(),
                        Station.builder(
                                        "KWLO-LP",
                                        Band.TRANSLATOR,
                                        new BigDecimal("103.5"),
                                        "Cedar Falls",
                                        Service.MUSIC)
                                .carries("KWLO")
                                .build()));
    }

    public static AccountBook accounts() {
        AccountBook book = new AccountBook();
        book.addAgency(new Agency("MOP", "Mopar Media"));
        book.addAgency(new Agency("CRS", "Crossroads Buying", new BigDecimal("0.15")));
        book.addAgency(new Agency("HSE", "House", new BigDecimal("0")));
        // The dealers all carry AUTO as well as their brand, which is how a real book is
        // written: two dealers of any make separate from each other.
        book.addAdvertiser(Advertiser.build("HYVEE", "Hy-Vee", "GROCERY,FUEL.GAS"));
        book.addAdvertiser(Advertiser.build("CASEY", "Caseys", "FUEL,GROCERY.CONV"));
        book.addAdvertiser(
                Advertiser.build("DEALF", "Dahl Ford", "AUTO,BRAND.FORD").withAgency("MOP"));
        book.addAdvertiser(Advertiser.build("DEALC", "Community Chevrolet", "AUTO,BRAND.CHEV")
                .withAgency("MOP"));
        book.addAdvertiser(Advertiser.build("DEALT", "Toyota of Waterloo", "AUTO,BRAND.TOYO"));
        book.addAdvertiser(Advertiser.build("SLUMB", "Slumberland", "FURNITURE").withAgency("CRS"));
        book.addAdvertiser(Advertiser.build("VGM", "VGM Group", "SERVICES.HOME"));
        book.addAdvertiser(Advertiser.build("BLACK", "Black Hawk Bank", "FINANCE.BANK"));
        return book;
    }

    public static Map<String, ClockGrid> programming() {
        Map<String, ClockGrid> grids = new LinkedHashMap<>();
        grids.put("KWLO", aGrid("KWLO"));
        grids.put("KOEL-FM", aGrid("KOEL-FM"));
        grids.put("KCRR", aGrid("KCRR", "TLK"));
        grids.put("KXEL", aGrid("KXEL", "TLK"));
        grids.put("KCNZ", aGrid("KCNZ", "TLK"));
        grids.get("KCNZ").assign(Assignment.build("farm block", "M-Sa", 5, 7, "FRM"));
        grids.get("KCRR").assign(Assignment.preempt("friday football", "F", 19, 23));
        return grids;
    }

    public static RateCardBook cards() {
        RateCardBook book = new RateCardBook();
        book.file(aCard("KWLO-25", "KWLO", 60, 32));
        book.file(aCard("KOEL-25", "KOEL-FM", 48, 26));
        book.file(aCard("KCRR-25", "KCRR", 40, 22));
        book.file(aCard("KXEL-25", "KXEL", 36, 20));
        book.file(aCard("KCNZ-25", "KCNZ", 30, 18));
        return book;
    }

    /** The house, built over the given tables so a test can reach into them afterwards. */
    public static House house(
            Cluster cluster, AccountBook accounts, RateCardBook cards, Map<String, ClockGrid> programming) {
        Map<String, DaypartGrid> dayparts = new LinkedHashMap<>();
        // A book per station, because a book is mutable and one station filing a rule must
        // not file it for the other four.
        Map<String, SeparationBook> separation = new LinkedHashMap<>();
        for (Station station : cluster.logged()) {
            dayparts.put(station.call(), DaypartGrid.standardGrid());
            separation.put(station.call(), SeparationBook.houseRules());
        }
        return new House(cluster, accounts, cards, dayparts, programming, separation);
    }

    public static House house() {
        return house(cluster(), accounts(), cards(), programming());
    }

    /** The 5a hour on KCNZ. Markets at :15 and :45, so the breaks sit around them. */
    public static Clock farmClock() {
        return Clock.build(
                "FRM",
                "farm hour",
                List.of(
                        new Break(Duration.ZERO, 1, BreakKind.LEGAL_ID, Duration.ofSeconds(10), 1, false),
                        local(2, 6, 120, 4),
                        local(3, 18, 180, 6),
                        local(4, 33, 180, 6),
                        local(5, 48, 120, 4)));
    }

    public static ClockGrid aGrid(String call) {
        return aGrid(call, "MUS");
    }

    public static ClockGrid aGrid(String call, String weekdayClock) {
        ClockGrid grid = new ClockGrid(call, CARD_DATE);
        grid.fileClock(Clock.musicClock());
        grid.fileClock(Clock.talkClock());
        grid.fileClock(farmClock());
        grid.assign(Assignment.build("all week", "M-Su", 0, 24, weekdayClock));
        return grid;
    }

    public static RateCard aCard(String code, String station, int drive, int midday) {
        return RateCard.build(
                code,
                station,
                CARD_DATE,
                List.of(
                        row("AMD", FIXED, drive),
                        row("AMD", NOTICE, rounded(drive * 0.8)),
                        row("AMD", IMMEDIATE, rounded(drive * 0.6)),
                        row("MID", FIXED, midday),
                        row("MID", NOTICE, rounded(midday * 0.8)),
                        row("MID", IMMEDIATE, rounded(midday * 0.6)),
                        row("PMD", FIXED, drive),
                        row("PMD", NOTICE, rounded(drive * 0.8)),
                        row("PMD", IMMEDIATE, rounded(drive * 0.6)),
                        row("EVE", IMMEDIATE, rounded(midday * 0.4)),
                        row("SAT", NOTICE, midday),
                        row("SUN", NOTICE, rounded(midday * 0.8)),
                        row("OVN", IMMEDIATE, 6),
                        row("AAA", NOTICE, rounded((drive + midday) / 2.0)),
                        row("ROS", IMMEDIATE, 12)));
    }

    /** Python's {@code round()}: to the nearest whole number, a tie going to the even one. */
    static long rounded(double value) {
        return (long) Math.rint(value);
    }
}
