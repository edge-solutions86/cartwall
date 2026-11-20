package io.github.rkaushikdev.cartwall.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One signal, with the paperwork that constrains what can air on it.
 *
 * <p>The two rules that leak into everything else: an AM daytimer signs off at local sunset and
 * cannot carry a spot after it, and a translator has no log of its own. It carries whatever its
 * parent carries, so a spot bought on the translator is really a spot on the parent and has to be
 * scheduled there.
 *
 * @param call the call sign
 * @param band AM, FM or translator
 * @param frequency megahertz on FM, kilohertz on AM
 * @param cityOfLicense the city the licence names, read out in the legal ID
 * @param service the programming the signal carries
 * @param daytimer signs off at sunset; AM only
 * @param carries the call sign of the signal this one simulcasts; translators always have one
 * @param commercialLoad minutes of commercial matter the format will bear in an hour
 */
public record Station(
        String call,
        Band band,
        BigDecimal frequency,
        String cityOfLicense,
        Service service,
        boolean daytimer,
        Optional<String> carries,
        int commercialLoad) {

    public static final int DEFAULT_COMMERCIAL_LOAD = 12;

    private static final Pattern CALL_SIGN = Pattern.compile("^[KW][A-Z]{2,3}(?:-(?:FM|AM|LP))?$");

    /** FM sits on odd tenths only, 88.1 through 107.9. */
    private static final BigDecimal FM_LOW = new BigDecimal("88.1");

    private static final BigDecimal FM_HIGH = new BigDecimal("107.9");

    /** AM is 10 kHz spaced, 540 through 1700. */
    private static final int AM_LOW = 540;

    private static final int AM_HIGH = 1700;
    private static final int AM_SPACING = 10;

    public Station {
        Objects.requireNonNull(carries, "carries");
        if (!CALL_SIGN.matcher(call).matches()) {
            throw new StationError("'" + call + "' is not a call sign");
        }
        checkFrequency(band, frequency);
        if (daytimer && band != Band.AM) {
            throw new StationError(call + " is not AM, so it cannot be a daytimer");
        }
        if (band == Band.TRANSLATOR
                && carries.filter(parent -> !parent.isEmpty()).isEmpty()) {
            throw new StationError(call + " is a translator with nothing to carry");
        }
        if (carries.filter(call::equals).isPresent()) {
            throw new StationError(call + " cannot carry itself");
        }
        if (commercialLoad < 0 || commercialLoad > 60) {
            throw new StationError(commercialLoad + " minutes an hour is not a load");
        }
    }

    /** A builder for the paperwork, with the house defaults for everything optional. */
    public static Builder builder(String call, Band band, BigDecimal frequency, String cityOfLicense, Service service) {
        return new Builder(call, band, frequency, cityOfLicense, service);
    }

    /** True when the signal has no log of its own. */
    public boolean isSatellite() {
        return carries.isPresent();
    }

    /** {@code 99.1 FM} or {@code 1540 AM}, the way the dial reads. */
    public String dial() {
        if (band.isFm()) {
            return frequency.toPlainString() + " FM";
        }
        return frequency.intValue() + " AM";
    }

    /** What has to air within a few minutes of the top of every hour. */
    public String legalId() {
        return call + ", " + cityOfLicense;
    }

    @Override
    public String toString() {
        return call;
    }

    private static void checkFrequency(Band band, BigDecimal frequency) {
        if (band.isFm()) {
            if (frequency.compareTo(FM_LOW) < 0 || frequency.compareTo(FM_HIGH) > 0) {
                throw new StationError(frequency.toPlainString() + " is off the FM band");
            }
            int tenths = frequency.multiply(BigDecimal.TEN).intValue() % 10;
            if (tenths % 2 == 0) {
                throw new StationError(frequency.toPlainString() + " is not an FM channel, tenths run odd");
            }
            return;
        }
        int khz = frequency.intValue();
        if (frequency.compareTo(BigDecimal.valueOf(khz)) != 0) {
            throw new StationError(frequency.toPlainString() + " is not a whole number of kilohertz");
        }
        if (khz < AM_LOW || khz > AM_HIGH) {
            throw new StationError(khz + " is off the AM band");
        }
        if (khz % AM_SPACING != 0) {
            throw new StationError(khz + " is not on a 10 kHz channel");
        }
    }

    /** Collects a station's optional paperwork before it is checked as a whole. */
    public static final class Builder {

        private final String call;
        private final Band band;
        private final BigDecimal frequency;
        private final String cityOfLicense;
        private final Service service;
        private boolean daytimer;
        private String carries;
        private int commercialLoad = DEFAULT_COMMERCIAL_LOAD;

        private Builder(String call, Band band, BigDecimal frequency, String cityOfLicense, Service service) {
            this.call = call;
            this.band = band;
            this.frequency = frequency;
            this.cityOfLicense = cityOfLicense;
            this.service = service;
        }

        public Builder daytimer(boolean daytimer) {
            this.daytimer = daytimer;
            return this;
        }

        public Builder carries(String carries) {
            this.carries = carries;
            return this;
        }

        public Builder commercialLoad(int commercialLoad) {
            this.commercialLoad = commercialLoad;
            return this;
        }

        public Station build() {
            return new Station(
                    call,
                    band,
                    frequency,
                    cityOfLicense,
                    service,
                    daytimer,
                    Optional.ofNullable(carries),
                    commercialLoad);
        }
    }
}
