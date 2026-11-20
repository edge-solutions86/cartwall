package io.github.rkaushikdev.cartwall.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything under one licence holder in one market.
 *
 * <p>Built checked: no call sign twice, every simulcast parent in the cluster, and no simulcast
 * chain that loops back on itself.
 *
 * @param market the market the cluster sells
 * @param stations every signal, in the order the house lists them
 */
public record Cluster(String market, List<Station> stations) implements Iterable<Station> {

    public Cluster {
        stations = List.copyOf(stations);
        Set<String> known = new HashSet<>();
        Set<String> duplicated = new TreeSet<>();
        for (Station station : stations) {
            if (!known.add(station.call())) {
                duplicated.add(station.call());
            }
        }
        if (!duplicated.isEmpty()) {
            throw new StationError(String.join(", ", duplicated) + " appears twice in the cluster");
        }
        for (Station station : stations) {
            String parent = station.carries().orElse(null);
            if (parent != null && !known.contains(parent)) {
                throw new StationError(station.call() + " carries " + parent + ", which is not in the cluster");
            }
        }
        for (Station station : stations) {
            walkToALog(stations, station);
        }
    }

    /** The signal with this call sign. */
    public Station station(String call) {
        for (Station station : stations) {
            if (station.call().equals(call)) {
                return station;
            }
        }
        throw new StationError(call + " is not one of ours");
    }

    public boolean has(String call) {
        return stations.stream().anyMatch(station -> station.call().equals(call));
    }

    /** The signals that get a log built for them. */
    public List<Station> logged() {
        return stations.stream().filter(station -> !station.isSatellite()).toList();
    }

    /** Signals that simulcast {@code call}, directly or through another. */
    public List<Station> carriedBy(String call) {
        List<Station> found = new ArrayList<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.push(call);
        while (!pending.isEmpty()) {
            String parent = pending.pop();
            for (Station station : stations) {
                if (station.carries().filter(parent::equals).isPresent() && !found.contains(station)) {
                    found.add(station);
                    pending.push(station.call());
                }
            }
        }
        return List.copyOf(found);
    }

    /** Every signal a spot bought on {@code call} actually reaches, the logged one first. */
    public List<Station> airsOn(String call) {
        Station parent = loggingStation(call);
        List<Station> reached = new ArrayList<>();
        reached.add(parent);
        reached.addAll(carriedBy(parent.call()));
        return List.copyOf(reached);
    }

    /** The signal whose log a spot bought on {@code call} has to be placed in. */
    public Station loggingStation(String call) {
        return walkToALog(stations, station(call));
    }

    public int size() {
        return stations.size();
    }

    @Override
    public Iterator<Station> iterator() {
        return stations.iterator();
    }

    /** Follow the simulcast chain up to the signal that owns the log. */
    private static Station walkToALog(List<Station> stations, Station station) {
        List<String> seen = new ArrayList<>(List.of(station.call()));
        Station current = station;
        while (current.carries().isPresent()) {
            String parent = current.carries().get();
            if (seen.contains(parent)) {
                throw new StationError(String.join(" carries ", seen) + " loops back on itself");
            }
            seen.add(parent);
            current = stations.stream()
                    .filter(candidate -> candidate.call().equals(parent))
                    .findFirst()
                    .orElseThrow();
        }
        return current;
    }
}
