package io.github.rkaushikdev.cartwall.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code cartwall serve} answers over HTTP until it is stopped, and then the command returns. */
class ServeTest {

    private static final Pattern LISTENING = Pattern.compile("serving .* on http://127\\.0\\.0\\.1:(\\d+)");

    @TempDir
    Path tmp;

    @Test
    void theServiceAnswersUntilItIsStoppedAndThenTheCommandReturns() throws Exception {
        String url = Shell.fileUrl(tmp, "serve");
        Shell.seed(url, uow -> {});
        StringWriter out = new StringWriter();
        Terminal terminal = new Terminal(
                Map.<String, String>of()::get,
                new BufferedReader(new StringReader("")),
                new PrintWriter(out, true),
                new PrintWriter(new StringWriter(), true));
        AtomicInteger code = new AtomicInteger(-1);
        Thread serving = new Thread(() -> code.set(Main.run(terminal, "-d", url, "serve", "--port", "0")));
        serving.start();
        try {
            int port = portOnceListening(out);
            try (HttpClient http = HttpClient.newHttpClient()) {
                HttpResponse<String> health = http.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(health.statusCode()).isEqualTo(200);
                assertThat(health.body()).contains("\"status\":\"up\"");
            }
        } finally {
            serving.interrupt();
            serving.join(Duration.ofSeconds(60));
        }
        assertThat(serving.isAlive()).isFalse();
        assertThat(code.get()).isEqualTo(Main.OK);
    }

    private static int portOnceListening(StringWriter out) throws InterruptedException {
        Instant giveUp = Instant.now().plusSeconds(120);
        while (Instant.now().isBefore(giveUp)) {
            Matcher listening = LISTENING.matcher(out.toString());
            if (listening.find()) {
                return Integer.parseInt(listening.group(1));
            }
            Thread.sleep(100);
        }
        throw new AssertionError("serve never said where it was listening: " + out);
    }
}
