package io.github.rkaushikdev.cartwall.cli;

/** The operator said no at a prompt, or walked away from it. A refusal, not a crash. */
final class Stopped extends RuntimeException {

    private static final long serialVersionUID = 1L;

    Stopped() {
        super("stopped");
    }
}
