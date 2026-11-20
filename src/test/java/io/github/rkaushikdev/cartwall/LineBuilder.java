package io.github.rkaushikdev.cartwall;

import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.Flight;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import java.time.LocalDate;
import java.util.List;

/**
 * The line most tests write: a KWLO morning drive fixed thirty at $60, twelve a week, Monday to
 * Friday, 8/4 to 8/29. A test changes only what it is about.
 */
public final class LineBuilder {

    private int number = 1;
    private String station = "KWLO";
    private String daypart = "AMD";
    private ClassOfTime grade = ClassOfTime.FIXED;
    private SpotLength length = SpotLength.THIRTY;
    private Money rate = Money.dollars(60);
    private Flight flight = Flight.build(Fixtures.MONDAY, LocalDate.of(2025, 8, 29), "M-F", 12);
    private List<String> carts = List.of();

    LineBuilder() {}

    public LineBuilder number(int number) {
        this.number = number;
        return this;
    }

    public LineBuilder station(String station) {
        this.station = station;
        return this;
    }

    public LineBuilder daypart(String daypart) {
        this.daypart = daypart;
        return this;
    }

    public LineBuilder grade(ClassOfTime grade) {
        this.grade = grade;
        return this;
    }

    public LineBuilder length(SpotLength length) {
        this.length = length;
        return this;
    }

    public LineBuilder rate(Money rate) {
        this.rate = rate;
        return this;
    }

    public LineBuilder rate(String rate) {
        return rate(Money.parse(rate));
    }

    public LineBuilder flight(Flight flight) {
        this.flight = flight;
        return this;
    }

    public LineBuilder flight(LocalDate first, LocalDate last, String days, int perWeek) {
        return flight(Flight.build(first, last, days, perWeek));
    }

    public LineBuilder carts(String... carts) {
        this.carts = List.of(carts);
        return this;
    }

    public Line build() {
        return new Line(number, station, daypart, grade, length, rate, flight, carts);
    }
}
