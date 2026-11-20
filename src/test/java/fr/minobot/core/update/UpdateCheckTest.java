package fr.minobot.core.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** What the check makes of what the feed says, and when it does not ask at all. */
class UpdateCheckTest {

    private final AtomicReference<Optional<String>> published = new AtomicReference<>(Optional.empty());

    private UpdateCheck running(String version) {
        return new UpdateCheck(version, published::get);
    }

    @Test
    @DisplayName("a newer release is offered")
    void offersANewerRelease() {
        final var check = running("1.9.1");
        published.set(Optional.of("1.9.2"));

        check.check();

        assertThat(check.available()).contains("1.9.2");
    }

    @Test
    @DisplayName("the release running, or an older one, is not")
    void doesNotOfferTheSameOrOlder() {
        final var check = running("1.9.1");

        published.set(Optional.of("1.9.1"));
        check.check();
        assertThat(check.available()).isEmpty();

        published.set(Optional.of("1.9.0"));
        check.check();
        assertThat(check.available()).isEmpty();
    }

    @Test
    @DisplayName("a feed that cannot be read leaves what the last look found")
    void keepsTheLastAnswerWhenTheFeedFails() {
        final var check = running("1.9.1");
        published.set(Optional.of("1.9.2"));
        check.check();

        published.set(Optional.empty());
        check.check();

        assertThat(check.available()).contains("1.9.2");
    }

    @Test
    @DisplayName("a tag that is not a version is not offered")
    void ignoresATagThatIsNoVersion() {
        final var check = running("1.9.1");
        published.set(Optional.of("nightly"));

        check.check();

        assertThat(check.available()).isEmpty();
    }

    @Test
    @DisplayName("the version running is given as the build gave it, and not at all when it has none")
    void tellsTheVersionRunning() {
        assertThat(running("1.9.1").running()).contains("1.9.1");
        assertThat(running(null).running()).isEmpty();
        assertThat(running("dev").running()).isEmpty();
    }

    @Test
    @DisplayName("a build that does not know its version never asks")
    void neverAsksWithoutAVersion() {
        final var asked = new AtomicInteger();
        final var check = new UpdateCheck(null, () -> {
            asked.incrementAndGet();
            return Optional.of("9.9.9");
        });

        check.check();
        check.start();

        assertThat(asked).hasValue(0);
        assertThat(check.available()).isEmpty();
    }
}
