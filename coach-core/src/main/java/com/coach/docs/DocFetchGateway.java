package com.coach.docs;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP boundary for fetching official documentation pages — the docs analog of the
 * {@code Sdk*Gateway} classes: real at runtime, replaced by a {@code @MockitoBean}
 * in tests.
 */
@Component
public class DocFetchGateway {

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Fetch one documentation page as text; any non-2xx status is an error. */
    public String fetch(URI url) {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/markdown, text/plain")
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) return response.body();
            throw new UncheckedIOException(
                    new IOException("HTTP " + response.statusCode() + " fetching " + url));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted fetching " + url, e);
        }
    }
}
