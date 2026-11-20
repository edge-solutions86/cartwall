package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Candidate;
import io.github.rkaushikdev.cartwall.domain.RuleBook;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One date's board and the rules that go with it, which every pass needs together.
 *
 * <p>A board without its rule book is half a thing: it can say a break has room and cannot say
 * whether the spot is allowed in it. The lay also carries the separation neighbour list, which
 * the passes that put spots on the board keep level with what they put there.
 */
public final class Lay {

    private final DayBoard board;
    private final RuleBook book;
    private List<Candidate> neighbours = new ArrayList<>();

    public Lay(DayBoard board, RuleBook book) {
        if (!board.day().equals(book.on())) {
            throw new PlacerError("a board for " + board.day() + " came with rules for " + book.on());
        }
        if (!board.station().equals(book.call())) {
            throw new PlacerError(board.station() + "'s board came with " + book.call() + "'s rules");
        }
        this.board = board;
        this.book = book;
    }

    public DayBoard board() {
        return board;
    }

    public RuleBook book() {
        return book;
    }

    public LocalDate day() {
        return board.day();
    }

    /** The separation candidates on the board, as last primed and kept up to date by hand. */
    public List<Candidate> neighbours() {
        return neighbours;
    }

    /** Read the board once. After this the list is kept up to date by hand. */
    public void prime(AccountBook accounts) {
        neighbours = Candidates.boardCandidates(board, accounts);
    }

    /** Forget the neighbour list, so the next pass reads the board again. */
    void forget() {
        neighbours = new ArrayList<>();
    }

    void remember(Candidate placed) {
        neighbours.add(placed);
    }
}
