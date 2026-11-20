package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;
import static io.github.rkaushikdev.cartwall.persistence.Columns.NAME_LENGTH;

import io.github.rkaushikdev.cartwall.domain.Band;
import io.github.rkaushikdev.cartwall.domain.Service;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * One signal on file: the row behind a {@link io.github.rkaushikdev.cartwall.domain.Station}.
 *
 * <p>Station, daypart and clock are small, change twice a year and are read constantly, so they
 * are stored the way they are typed and parsed on the way out.
 */
@Entity
@Table(name = "station")
public class StationRow {

    @Id
    @Column(name = "call", length = CODE_LENGTH, nullable = false)
    private String call;

    @Column(name = "band", length = 32, nullable = false)
    private Band band;

    @Column(name = "frequency", precision = 6, scale = 1, nullable = false)
    private BigDecimal frequency;

    @Column(name = "city_of_license", length = NAME_LENGTH, nullable = false)
    private String cityOfLicense;

    @Column(name = "service", length = 32, nullable = false)
    private Service service;

    @Column(name = "market", length = NAME_LENGTH, nullable = false)
    private String market;

    @Column(name = "daytimer", nullable = false)
    private boolean daytimer;

    @Column(name = "carries", length = CODE_LENGTH)
    private String carries;

    @Column(name = "commercial_load", nullable = false)
    private int commercialLoad = 12;

    /** Declares the foreign key on {@code carries}. Written through the code column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "carries",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_station_carries_station"))
    private StationRow parent;

    protected StationRow() {}

    public StationRow(
            String call, Band band, BigDecimal frequency, String cityOfLicense, Service service, String market) {
        this.call = call;
        this.band = band;
        this.frequency = frequency;
        this.cityOfLicense = cityOfLicense;
        this.service = service;
        this.market = market;
    }

    public String getCall() {
        return call;
    }

    public Band getBand() {
        return band;
    }

    public void setBand(Band band) {
        this.band = band;
    }

    public BigDecimal getFrequency() {
        return frequency;
    }

    public void setFrequency(BigDecimal frequency) {
        this.frequency = frequency;
    }

    public String getCityOfLicense() {
        return cityOfLicense;
    }

    public void setCityOfLicense(String cityOfLicense) {
        this.cityOfLicense = cityOfLicense;
    }

    public Service getService() {
        return service;
    }

    public void setService(Service service) {
        this.service = service;
    }

    public String getMarket() {
        return market;
    }

    public void setMarket(String market) {
        this.market = market;
    }

    public boolean isDaytimer() {
        return daytimer;
    }

    public void setDaytimer(boolean daytimer) {
        this.daytimer = daytimer;
    }

    public String getCarries() {
        return carries;
    }

    public void setCarries(String carries) {
        this.carries = carries;
    }

    public int getCommercialLoad() {
        return commercialLoad;
    }

    public void setCommercialLoad(int commercialLoad) {
        this.commercialLoad = commercialLoad;
    }

    StationRow getParent() {
        return parent;
    }

    @Override
    public String toString() {
        return "<StationRow " + call + ">";
    }
}
