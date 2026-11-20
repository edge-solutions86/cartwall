package io.github.rkaushikdev.cartwall.persistence;

import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.LookupFailure;
import io.github.rkaushikdev.cartwall.domain.RateCard;
import io.github.rkaushikdev.cartwall.domain.RateCardBook;
import io.github.rkaushikdev.cartwall.persistence.models.RateCardRow;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;

/** Every rate card ever filed. */
public final class RateCardRepository {

    private final EntityManager entityManager;

    public RateCardRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** The entity manager this repository shares with the rest of its unit of work. */
    public EntityManager entityManager() {
        return entityManager;
    }

    /** File a card, replacing whatever the station had on file for the same date. */
    public void file(RateCard card) {
        List<RateCardRow> existing = forStationOn(card.station(), card.effective());
        if (!existing.isEmpty()) {
            entityManager.remove(existing.get(0));
            entityManager.flush();
        }
        entityManager.persist(Mappers.rateCardRow(card));
    }

    /** The card a station filed for one effective date. */
    public RateCard card(String station, LocalDate effective) {
        List<RateCardRow> rows = forStationOn(station, effective);
        if (rows.isEmpty()) {
            throw new LookupFailure(station + " has no card effective " + effective, Details.of("station", station));
        }
        return Mappers.rateCardFrom(rows.get(0));
    }

    /** Every card, by station then effective date. */
    public RateCardBook book() {
        return Mappers.rateCardBookFrom(entityManager
                .createQuery("select c from RateCardRow c order by c.station, c.effective", RateCardRow.class)
                .getResultList());
    }

    private List<RateCardRow> forStationOn(String station, LocalDate effective) {
        return entityManager
                .createQuery(
                        "select c from RateCardRow c where c.station = :station and c.effective = :effective",
                        RateCardRow.class)
                .setParameter("station", station)
                .setParameter("effective", effective)
                .getResultList();
    }
}
