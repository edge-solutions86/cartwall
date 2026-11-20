package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.Band;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.Credit;
import io.github.rkaushikdev.cartwall.domain.Service;
import io.github.rkaushikdev.cartwall.persistence.Columns;
import io.github.rkaushikdev.cartwall.persistence.TestDatabase;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountModelsTest {

    private static final ClassOfTime FIXED = ClassOfTime.FIXED;
    private static final ClassOfTime NOTICE = ClassOfTime.NOTICE;
    private static final LocalDate JANUARY = LocalDate.of(2025, 1, 1);

    private UnitOfWork session;
    private EntityManager em;

    @BeforeEach
    void openASession() {
        session = TestDatabase.fresh().unitOfWork();
        em = session.entityManager();
    }

    @AfterEach
    void closeTheSession() {
        session.close();
    }

    static AdvertiserRow anAdvertiser(String code, String name) {
        return new AdvertiserRow(code, name);
    }

    static AdvertiserRow anAdvertiser() {
        return anAdvertiser("HYVEE", "Hy-Vee");
    }

    static CutRow aCut(String cart, String isci, int tenths) {
        return new CutRow(cart, isci, THIRTY, new Duration(tenths), JANUARY);
    }

    private void aStation() {
        em.persist(new StationRow("KWLO", Band.FM, new BigDecimal("99.1"), "Waterloo", Service.MUSIC, "WCF"));
    }

    private <T> List<T> all(Class<T> type) {
        return em.createQuery("select x from " + type.getSimpleName() + " x", type)
                .getResultList();
    }

    @Test
    void aLengthGoesInAsSecondsAndComesBackAsALength() {
        Columns.Seconds column = new Columns.Seconds();
        assertThat(column.convertToDatabaseColumn(THIRTY)).isEqualTo(30);
        assertThat(column.convertToEntityAttribute(60)).isEqualTo(SIXTY);
        assertThat(column.convertToDatabaseColumn(null)).isNull();
        assertThat(column.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void anAgencyKeepsItsCommissionAsAnExactDecimal() {
        em.persist(new AgencyRow("MOP", "Mopar Media", new BigDecimal("0.1500"), false));
        session.commit();
        em.clear();
        AgencyRow found = em.find(AgencyRow.class, "MOP");
        assertThat(found.getRate()).isEqualByComparingTo("0.1500");
        assertThat(found.isAdvertiserLiable()).isFalse();
        assertThat(found).hasToString("<AgencyRow MOP>");
    }

    @Test
    void aDirectAdvertiserHasNoAgency() {
        em.persist(anAdvertiser());
        session.commit();
        em.clear();
        AdvertiserRow found = em.find(AdvertiserRow.class, "HYVEE");
        assertThat(found.getAgency()).isNull();
        assertThat(found.getCredit()).isSameAs(Credit.OPEN);
    }

    @Test
    void anAdvertiserCannotNameAnAgencyThatIsNotThere() {
        AdvertiserRow advertiser = anAdvertiser();
        advertiser.setAgency("NOPE");
        em.persist(advertiser);
        assertThatThrownBy(session::commit).isInstanceOf(PersistenceException.class);
    }

    @Test
    void creditStatusRoundTripsAsItsShortString() {
        AdvertiserRow advertiser = anAdvertiser();
        advertiser.setCredit(Credit.HOLD);
        em.persist(advertiser);
        session.commit();
        em.clear();
        assertThat(em.find(AdvertiserRow.class, "HYVEE").getCredit()).isSameAs(Credit.HOLD);
    }

    @Test
    void aChainStorePointsAtItsParent() {
        em.persist(anAdvertiser("HYVEE", "Hy-Vee"));
        AdvertiserRow store = anAdvertiser("HYVEE1", "Hy-Vee Kimball");
        store.setParent("HYVEE");
        em.persist(store);
        session.commit();
        assertThat(em.find(AdvertiserRow.class, "HYVEE1").getParent()).isEqualTo("HYVEE");
    }

    @Test
    void conflictCodesAreRowsSoARootCanBeQueried() {
        AdvertiserRow advertiser = anAdvertiser();
        advertiser.setCategories(List.of(new CategoryRow("GROCERY", "GROCERY"), new CategoryRow("FUEL.GAS", "FUEL")));
        em.persist(advertiser);
        em.persist(anAdvertiser("CASEY", "Caseys"));
        em.flush();
        em.persist(new CategoryRow("CASEY", "FUEL", "FUEL"));
        session.commit();
        List<String> onFuel = em.createQuery(
                        "select c.advertiser from CategoryRow c where c.root = 'FUEL' order by c.advertiser",
                        String.class)
                .getResultList();
        assertThat(onFuel).containsExactly("CASEY", "HYVEE");
    }

    @Test
    void theCodesComeBackInAStableOrder() {
        AdvertiserRow advertiser = anAdvertiser();
        advertiser.setCategories(List.of(
                new CategoryRow("PHARM", "PHARM"),
                new CategoryRow("FUEL.GAS", "FUEL"),
                new CategoryRow("GROCERY", "GROCERY")));
        em.persist(advertiser);
        session.commit();
        em.clear();
        assertThat(em.find(AdvertiserRow.class, "HYVEE").getCategories())
                .extracting(CategoryRow::getPath)
                .containsExactly("FUEL.GAS", "GROCERY", "PHARM");
    }

    @Test
    void oneAdvertiserCannotCarryTheSameCodeTwice() {
        AdvertiserRow advertiser = anAdvertiser();
        advertiser.setCategories(List.of(new CategoryRow("GROCERY", "GROCERY"), new CategoryRow("GROCERY", "GROCERY")));
        assertThatThrownBy(() -> {
                    em.persist(advertiser);
                    session.commit();
                })
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void closingAnAccountTakesItsCodesAndItsCopyWithIt() {
        AdvertiserRow advertiser = anAdvertiser();
        advertiser.setCategories(List.of(new CategoryRow("GROCERY", "GROCERY")));
        advertiser.setCuts(List.of(aCut("C1204", "HYVE2501", 299)));
        em.persist(advertiser);
        session.commit();
        em.remove(advertiser);
        session.commit();
        assertThat(all(CategoryRow.class)).isEmpty();
        assertThat(all(CutRow.class)).isEmpty();
    }

    @Test
    void aCutKeepsItsSoldLengthAndItsRunningTimeApart() {
        AdvertiserRow advertiser = anAdvertiser();
        advertiser.setCuts(List.of(aCut("C1204", "HYVE2501", 301)));
        em.persist(advertiser);
        session.commit();
        em.clear();
        CutRow found = em.find(CutRow.class, "C1204");
        assertThat(found.getLength()).isEqualTo(THIRTY);
        assertThat(found.getActual()).isEqualTo(new Duration(301));
        assertThat(found.getActual()).isNotEqualTo(found.getLength().air());
        assertThat(found).hasToString("<CutRow C1204>");
    }

    @Test
    void aKilledCutKeepsTheDateItStopped() {
        AdvertiserRow advertiser = anAdvertiser();
        CutRow cut = aCut("C1204", "HYVE2501", 300);
        cut.setLastAir(LocalDate.of(2025, 2, 28));
        cut.setKilled(true);
        advertiser.setCuts(List.of(cut));
        em.persist(advertiser);
        session.commit();
        CutRow found = em.find(CutRow.class, "C1204");
        assertThat(found.isKilled()).isTrue();
        assertThat(found.getLastAir()).isEqualTo(LocalDate.of(2025, 2, 28));
    }

    @Test
    void theCopyComesBackInCartOrder() {
        AdvertiserRow advertiser = anAdvertiser();
        advertiser.setCuts(List.of(aCut("C1300", "HYVE2503", 300), aCut("C1204", "HYVE2501", 300)));
        em.persist(advertiser);
        session.commit();
        em.clear();
        assertThat(em.find(AdvertiserRow.class, "HYVEE").getCuts())
                .extracting(CutRow::getCart)
                .containsExactly("C1204", "C1300");
    }

    @Test
    void aCardQuotesAThirtyAndStoresItInCents() {
        aStation();
        RateCardRow card = new RateCardRow("KWLO-25", "KWLO", JANUARY);
        card.setQuotes(
                List.of(new QuoteRow("AMD", FIXED, Money.dollars(60)), new QuoteRow("AMD", NOTICE, Money.dollars(48))));
        em.persist(card);
        session.commit();
        em.clear();
        RateCardRow found = all(RateCardRow.class).get(0);
        assertThat(found.getQuotes()).extracting(QuoteRow::getThirty).containsExactly(new Money(6000), new Money(4800));
        assertThat(found.getQuotes().get(0).getGrade()).isSameAs(FIXED);
    }

    @Test
    void aCardMayNotQuoteOneCellTwice() {
        aStation();
        RateCardRow card = new RateCardRow("KWLO-25", "KWLO", JANUARY);
        card.setQuotes(
                List.of(new QuoteRow("AMD", FIXED, Money.dollars(60)), new QuoteRow("AMD", FIXED, Money.dollars(55))));
        assertThatThrownBy(() -> {
                    em.persist(card);
                    session.commit();
                })
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void aStationMayNotHaveTwoCardsEffectiveTheSameDay() {
        aStation();
        em.persist(new RateCardRow("KWLO-25A", "KWLO", JANUARY));
        assertThatThrownBy(() -> {
                    em.persist(new RateCardRow("KWLO-25B", "KWLO", JANUARY));
                    session.commit();
                })
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void aStationMayFileASecondCardForALaterDay() {
        aStation();
        em.persist(new RateCardRow("KWLO-25A", "KWLO", JANUARY));
        em.persist(new RateCardRow("KWLO-25B", "KWLO", LocalDate.of(2025, 7, 1)));
        session.commit();
        assertThat(all(RateCardRow.class)).hasSize(2);
    }

    @Test
    void replacingACardTakesItsQuotesWithIt() {
        aStation();
        RateCardRow card = new RateCardRow("KWLO-25", "KWLO", JANUARY);
        card.setQuotes(List.of(new QuoteRow("AMD", FIXED, Money.dollars(60))));
        em.persist(card);
        session.commit();
        em.remove(card);
        session.commit();
        assertThat(all(QuoteRow.class)).isEmpty();
    }
}
