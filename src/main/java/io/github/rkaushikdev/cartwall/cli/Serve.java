package io.github.rkaushikdev.cartwall.cli;

import io.github.rkaushikdev.cartwall.Settings;
import io.github.rkaushikdev.cartwall.api.CartwallApplication;
import io.github.rkaushikdev.cartwall.api.KeyRing;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Run the HTTP service until it is stopped. The one command that starts Spring: it hands the
 * service the settings, the database it opened and the key ring the environment configures, and
 * waits until the service closes, on a signal or an interrupt.
 */
@Command(name = "serve", mixinStandardHelpOptions = true, description = "Run the HTTP service until it is stopped.")
final class Serve implements Callable<Integer> {

    @ParentCommand
    private Main main;

    @Option(names = "--host", defaultValue = "127.0.0.1", description = "Address to listen on.")
    private String host;

    @Option(names = "--port", defaultValue = "8000", description = "Port to listen on; 0 picks a free one.")
    private int port;

    @Override
    public Integer call() {
        Settings settings = main.settings();
        KeyRing keys = KeyRing.fromEnvironment(main.terminal().environment());
        CountDownLatch closed = new CountDownLatch(1);
        try (ConfigurableApplicationContext service = CartwallApplication.run(
                settings, main.database(), keys, "--server.port=" + port, "--server.address=" + host)) {
            service.addApplicationListener(event -> {
                if (event instanceof ContextClosedEvent) {
                    closed.countDown();
                }
            });
            int listening = ((ServletWebServerApplicationContext) service)
                    .getWebServer()
                    .getPort();
            main.terminal().out().println("serving " + settings.market() + " on http://" + host + ":" + listening);
            main.terminal().out().flush();
            if (service.isActive()) {
                closed.await();
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
        return Main.OK;
    }
}
