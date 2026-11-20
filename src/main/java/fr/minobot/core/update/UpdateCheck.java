package fr.minobot.core.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

/**
 * Whether a release newer than the one running has been published — asked at launch, and again every few
 * hours for a Minobot left running for days. It only ever says so: the player downloads it themselves.
 *
 * <p>A build that does not know its own version — run from the IDE, or from the tests — never asks: it
 * would have nothing to compare the answer to.
 */
public final class UpdateCheck {

    private static final Logger log = LoggerFactory.getLogger(UpdateCheck.class);

    /** Releases come out days apart; GitHub allows sixty unauthenticated requests an hour per address. */
    private static final Duration INTERVAL = Duration.ofHours(6);

    private final Optional<Version> running;

    /** The version as the build gave it, for the player to read. */
    private final Optional<String> label;
    private final ReleaseFeed feed;

    /** The newer release's tag, {@code null} while none is known. */
    private volatile String available;

    /** @param running the version of this build, {@code null} when it does not know it */
    public UpdateCheck(String running, ReleaseFeed feed) {
        this.running = Version.parse(running);
        this.label = this.running.map(_ -> running.strip());
        this.feed = feed;
    }

    /** Asks now and every {@link #INTERVAL} after, on a thread of its own: the network takes its time. */
    public void start() {
        if (running.isEmpty()) {
            log.info("This build does not know its version: no update check.");
            return;
        }
        Thread.ofVirtual().name("update-check").start(() -> {
            try {
                while (true) {
                    check();
                    Thread.sleep(INTERVAL);
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** One look at the feed. A look that fails leaves what the last one found. */
    void check() {
        if (running.isEmpty()) {
            return;
        }
        feed.latest().ifPresent(tag -> {
            final var newer = Version.parse(tag).filter(latest -> latest.isNewerThan(running.get())).isPresent();
            if (newer && !tag.equals(available)) {
                log.info("Minobot {} is available (running {}).", tag, running.get());
            }
            available = newer ? tag : null;
        });
    }

    /** The version of this build — {@code "1.9.1"} — when it knows it. */
    public Optional<String> running() {
        return label;
    }

    /** The tag of a release newer than this one, if one has been seen. */
    public Optional<String> available() {
        return Optional.ofNullable(available);
    }
}
