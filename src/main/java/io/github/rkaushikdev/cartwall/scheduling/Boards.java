package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.domain.House;
import io.github.rkaushikdev.cartwall.domain.RuleBook;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * One board per station per date, built once and shared for the length of a run.
 *
 * <p>The cache is the point. Line two of an order has to see what line one did, or the two of
 * them sit four minutes apart and the client hears their own commercial twice. A run that rebuilds
 * the board per line is not slow, it is wrong.
 *
 * <p>Open for extension: the log builder starts each board from what is already stored.
 */
public class Boards {

    private final House house;
    private final Map<StationDay, DayBoard> boards = new HashMap<>();
    private final Map<StationDay, RuleBook> books = new HashMap<>();
    private final Map<StationDay, Lay> lays = new HashMap<>();

    public Boards(House house) {
        this.house = house;
    }

    public House house() {
        return house;
    }

    public RuleBook book(String call, LocalDate day) {
        return books.computeIfAbsent(new StationDay(call, day), key -> house.book(call, day));
    }

    public DayBoard board(String call, LocalDate day) {
        StationDay key = new StationDay(call, day);
        DayBoard board = boards.get(key);
        if (board == null) {
            board = freshBoard(call, day);
            boards.put(key, board);
        }
        return board;
    }

    public Lay lay(String call, LocalDate day) {
        StationDay key = new StationDay(call, day);
        Lay lay = lays.get(key);
        if (lay == null) {
            lay = new Lay(board(call, day), book(call, day));
            lays.put(key, lay);
        }
        return lay;
    }

    /** Every station day this run actually touched, in a stable order. */
    public List<StationDay> built() {
        return List.copyOf(new TreeSet<>(boards.keySet()));
    }

    public List<LocalDate> days() {
        TreeSet<LocalDate> days = new TreeSet<>();
        boards.keySet().forEach(key -> days.add(key.day()));
        return List.copyOf(days);
    }

    public List<String> stations() {
        TreeSet<String> calls = new TreeSet<>();
        boards.keySet().forEach(key -> calls.add(key.call()));
        return List.copyOf(calls);
    }

    public int size() {
        return boards.size();
    }

    /** The board a station day starts from before anything is placed: built from the grid. */
    protected DayBoard freshBoard(String call, LocalDate day) {
        return DayBoard.build(house.programming().get(call), day);
    }
}
