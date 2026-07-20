package com.coach.docs;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link DocFetchGateway}'s real {@link java.net.http.HttpClient} against an
 * in-process JDK {@link HttpServer} on an ephemeral port — verifying success bodies,
 * the {@code Accept} header, redirect following, and non-2xx / connection-failure errors.
 */
class DocFetchGatewayTest {

    private final DocFetchGateway gateway = new DocFetchGateway();
    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void returnsBodyAndSendsMarkdownAcceptHeaderOn200() {
        var accept = new AtomicReference<String>();
        server.createContext("/doc.md", exchange -> {
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            respond(exchange, 200, "DOC BODY");
        });

        assertThat(gateway.fetch(url("/doc.md"))).isEqualTo("DOC BODY");
        assertThat(accept.get()).isEqualTo("text/markdown, text/plain");
    }

    @Test
    void followsRedirectAndReturnsTargetBody() {
        server.createContext("/moved.md", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target.md");
            respond(exchange, 302, "");
        });
        server.createContext("/target.md", exchange -> respond(exchange, 200, "REDIRECTED BODY"));

        assertThat(gateway.fetch(url("/moved.md"))).isEqualTo("REDIRECTED BODY");
    }

    @Test
    void notFoundStatusThrowsWithStatusAndUrl() {
        server.createContext("/missing.md", exchange -> respond(exchange, 404, "nope"));
        var target = url("/missing.md");

        assertThatThrownBy(() -> gateway.fetch(target))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("HTTP 404")
                .hasMessageContaining(target.toString());
    }

    @Test
    void serverErrorStatusThrowsWithStatus() {
        server.createContext("/boom.md", exchange -> respond(exchange, 500, "boom"));

        assertThatThrownBy(() -> gateway.fetch(url("/boom.md")))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void connectionFailureThrowsUncheckedIoException() {
        server.stop(0);

        assertThatThrownBy(() -> gateway.fetch(url("/doc.md")))
                .isInstanceOf(UncheckedIOException.class);
    }
}
