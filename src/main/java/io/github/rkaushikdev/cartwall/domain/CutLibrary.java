package io.github.rkaushikdev.cartwall.domain;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Everything production has dubbed, live and dead, by cart number. */
public final class CutLibrary {

    private final Map<String, Cut> cuts = new LinkedHashMap<>();

    /** File a new cut. A cart number is filed once. */
    public void file(Cut cut) {
        if (cuts.containsKey(cut.cart())) {
            throw new SpotError("cart " + cut.cart() + " is already filed");
        }
        cuts.put(cut.cart(), cut);
    }

    /** Replace a cut in place. Production redubs against the same cart. */
    public void refile(Cut cut) {
        if (!cuts.containsKey(cut.cart())) {
            throw new SpotError("cart " + cut.cart() + " has never been filed");
        }
        cuts.put(cut.cart(), cut);
    }

    public Cut cut(String cart) {
        Cut cut = cuts.get(cart);
        if (cut == null) {
            throw new SpotError("cart " + cart + " is not in the library");
        }
        return cut;
    }

    /** Stop a cut after {@code on} without losing what it was or what it ran. */
    public Cut kill(String cart, LocalDate on) {
        Cut killed = cut(cart).killedAfter(on);
        cuts.put(cart, killed);
        return killed;
    }

    /** Cuts eligible to air on {@code day}, in cart order. */
    public List<Cut> running(LocalDate day) {
        return running(day, Optional.empty());
    }

    /** One advertiser's cuts eligible to air on {@code day}, in cart order. */
    public List<Cut> running(LocalDate day, String advertiser) {
        return running(day, Optional.of(advertiser));
    }

    private List<Cut> running(LocalDate day, Optional<String> advertiser) {
        return cuts.values().stream()
                .filter(cut -> cut.runsOn(day))
                .filter(cut -> advertiser.map(cut.advertiser()::equals).orElse(true))
                .sorted(Comparator.comparing(Cut::cart))
                .toList();
    }

    /** Every cut, live or dead, one advertiser has had dubbed, in cart order. */
    public List<Cut> forAdvertiser(String advertiser) {
        return cuts.values().stream()
                .filter(cut -> cut.advertiser().equals(advertiser))
                .sorted(Comparator.comparing(Cut::cart))
                .toList();
    }

    /** Which sold lengths this advertiser can actually be scheduled in on {@code day}. */
    public Set<SpotLength> lengthsOnHand(String advertiser, LocalDate day) {
        return running(day, advertiser).stream().map(Cut::length).collect(Collectors.toUnmodifiableSet());
    }

    /** Every cut, in the order filed. */
    public List<Cut> cuts() {
        return List.copyOf(cuts.values());
    }

    public int size() {
        return cuts.size();
    }

    public boolean contains(String cart) {
        return cuts.containsKey(cart);
    }
}
