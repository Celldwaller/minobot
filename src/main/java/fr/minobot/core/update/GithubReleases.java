package fr.minobot.core.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/** The releases published on the project's GitHub page, read through GitHub's public API. */
public final class GithubReleases implements ReleaseFeed {

    private static final Logger log = LoggerFactory.getLogger(GithubReleases.class);

    private static final URI LATEST = URI.create("https://api.github.com/repos/Celldwaller/minobot/releases/latest");

    /** A check nobody waits on, but a connection left hanging would hold its thread for nothing. */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public Optional<String> latest() {
        // GitHub refuses a request with no User-Agent.
        final var request = HttpRequest.newBuilder(LATEST)
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Minobot")
                .GET()
                .build();
        try {
            final var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("GitHub answered {} for the latest release.", response.statusCode());
                return Optional.empty();
            }
            final var tag = MAPPER.readTree(response.body()).path("tag_name").asText("");
            return tag.isBlank() ? Optional.empty() : Optional.of(tag);
        } catch (IOException e) {
            log.debug("Could not read the latest release: {}", e.getMessage());
            return Optional.empty();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
