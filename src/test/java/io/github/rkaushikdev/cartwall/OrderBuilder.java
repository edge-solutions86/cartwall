package io.github.rkaushikdev.cartwall;

import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.domain.OrderStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The order most tests write: 25-1044 for Hy-Vee, written 7/21 by DHOLM, pending, with one default
 * {@link LineBuilder line} on it.
 */
public final class OrderBuilder {

    private String number = "25-1044";
    private String advertiser = "HYVEE";
    private LocalDate written = LocalDate.of(2025, 7, 21);
    private String salesperson = "DHOLM";
    private List<Line> lines = List.of(new LineBuilder().build());
    private OrderStatus status = OrderStatus.PENDING;
    private String agency;
    private String campaign = "";

    OrderBuilder() {}

    public OrderBuilder number(String number) {
        this.number = number;
        return this;
    }

    public OrderBuilder advertiser(String advertiser) {
        this.advertiser = advertiser;
        return this;
    }

    public OrderBuilder written(LocalDate written) {
        this.written = written;
        return this;
    }

    public OrderBuilder salesperson(String salesperson) {
        this.salesperson = salesperson;
        return this;
    }

    public OrderBuilder lines(Line... lines) {
        this.lines = List.of(lines);
        return this;
    }

    public OrderBuilder lines(List<Line> lines) {
        this.lines = List.copyOf(lines);
        return this;
    }

    public OrderBuilder status(OrderStatus status) {
        this.status = status;
        return this;
    }

    public OrderBuilder agency(String agency) {
        this.agency = agency;
        return this;
    }

    public OrderBuilder campaign(String campaign) {
        this.campaign = campaign;
        return this;
    }

    public Order build() {
        return new Order(
                number, advertiser, written, salesperson, lines, status, Optional.ofNullable(agency), campaign);
    }
}
