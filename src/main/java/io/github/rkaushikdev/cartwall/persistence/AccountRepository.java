package io.github.rkaushikdev.cartwall.persistence;

import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.LookupFailure;
import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Advertiser;
import io.github.rkaushikdev.cartwall.domain.Agency;
import io.github.rkaushikdev.cartwall.domain.Cut;
import io.github.rkaushikdev.cartwall.domain.CutLibrary;
import io.github.rkaushikdev.cartwall.persistence.models.AdvertiserRow;
import io.github.rkaushikdev.cartwall.persistence.models.AgencyRow;
import io.github.rkaushikdev.cartwall.persistence.models.CutRow;
import jakarta.persistence.EntityManager;
import java.util.Comparator;
import java.util.List;

/** Advertisers, agencies and the copy library. */
public final class AccountRepository {

    private final EntityManager entityManager;

    public AccountRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** The entity manager this repository shares with the rest of its unit of work. */
    public EntityManager entityManager() {
        return entityManager;
    }

    /**
     * File the whole book, replacing every advertiser on file under the same code.
     *
     * <p>Replacing an advertiser replaces its codes and its copy with it, the way the original's
     * delete-then-add did.
     */
    public void saveBook(AccountBook book) {
        for (Agency agency : book.agencies().values()) {
            entityManager.merge(Mappers.agencyRow(agency));
        }
        entityManager.flush();
        for (Advertiser advertiser : book.advertisers().values()) {
            AdvertiserRow existing = entityManager.find(AdvertiserRow.class, advertiser.code());
            if (existing != null) {
                entityManager.remove(existing);
                entityManager.flush();
            }
        }
        book.advertisers().values().stream()
                .sorted(Comparator.comparing(
                                (Advertiser advertiser) -> advertiser.parent().isPresent())
                        .thenComparing(Advertiser::code))
                .forEach(advertiser -> entityManager.persist(Mappers.advertiserRow(advertiser)));
    }

    /** Every agency and advertiser on file, parents before the stores that belong to them. */
    public AccountBook book() {
        List<AgencyRow> agencies = entityManager
                .createQuery("select a from AgencyRow a order by a.code", AgencyRow.class)
                .getResultList();
        List<AdvertiserRow> advertisers = entityManager
                .createQuery(
                        "select a from AdvertiserRow a order by case when a.parent is null then 0 else 1 end, a.code",
                        AdvertiserRow.class)
                .getResultList();
        return Mappers.accountBookFrom(agencies, advertisers);
    }

    /** File a cut, or replace the one filed under the same cart. */
    public void fileCut(Cut cut) {
        entityManager.merge(Mappers.cutRow(cut));
    }

    public Cut cut(String cart) {
        CutRow row = entityManager.find(CutRow.class, cart);
        if (row == null) {
            throw new LookupFailure("cart " + cart + " is not in the library", Details.of("cart", cart));
        }
        return Mappers.cutFrom(row);
    }

    /** Every cut on file, in cart order. */
    public CutLibrary library() {
        return Mappers.cutLibraryFrom(entityManager
                .createQuery("select c from CutRow c order by c.cart", CutRow.class)
                .getResultList());
    }

    /** One advertiser's cuts, in cart order. */
    public CutLibrary library(String advertiser) {
        return Mappers.cutLibraryFrom(entityManager
                .createQuery("select c from CutRow c where c.advertiser = :advertiser order by c.cart", CutRow.class)
                .setParameter("advertiser", advertiser)
                .getResultList());
    }
}
