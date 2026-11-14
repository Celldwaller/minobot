package fr.minobot.feature.notification;

import fr.minobot.app.Settings;
import fr.minobot.app.TestConfigs;
import fr.minobot.core.FocusManager;
import fr.minobot.core.NotificationManager;
import fr.minobot.core.WhisperLog;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.Notification;
import fr.minobot.core.domain.Whisper;
import fr.minobot.core.input.FakeInput;
import fr.minobot.ui.FakeToastView;
import fr.minobot.ui.ToastContent;
import fr.minobot.win32.FakeWindowApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * What becomes a whisper card, what a click on one does, and how a card fades on its own.
 *
 * <p>The stack is drawn by a follow thread on a 30&nbsp;ms poll, so a test lets it settle before reading
 * the view — and focusing a background window makes the real {@link FocusManager} sleep through its ALT
 * dance, so the timeout is generous, like the trade-accepter's tests.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class WhisperToasterTest {

    private final FakeWindowApi api = new FakeWindowApi()
            .withWindow(1, "Alpha - Dofus Retro v1.48")
            .withWindow(2, "Bravo - Dofus Retro v1.48")
            .withForeground(2); // the player is on Bravo

    private final Settings settings = TestConfigs.settings(Map.of());
    private final NotificationManager notifications = new NotificationManager(Path.of("no-such-database.db"));
    private final WhisperLog whispers = new WhisperLog();

    private FakeToastView view;

    private WhisperToaster toaster() {
        return toaster(WhisperToaster.TOAST_LIFETIME);
    }

    private WhisperToaster toaster(Duration lifetime) {
        final var windows = new WindowManager(api, settings);
        final var focus = new FocusManager(api, new FakeInput(api::foregroundWindow));
        return new WhisperToaster(api, windows, focus, settings, notifications, whispers, lifetime,
                actions -> view = new FakeToastView(actions));
    }

    /** The follow loop polls every 30 ms; this is room for several turns of it. */
    private void settle() throws InterruptedException {
        Thread.sleep(250);
    }

    private static Notification whisper(String receiver, String sender, String message) {
        return new Notification(receiver + " - Dofus Retro", "de " + sender + " : " + message);
    }

    @Test
    @DisplayName("a whisper becomes a card naming its receiver, its sender and the line")
    void aWhisperBecomesACard() throws InterruptedException {
        final var toaster = toaster();

        toaster.onNotification(whisper("Bravo", "Alpha", "Bonjour c'est toto"));
        settle();

        assertThat(view.cards()).hasSize(1);
        final var card = view.cards().getFirst();
        assertThat(card.receiver()).isEqualTo("Bravo");
        assertThat(card.sender()).isEqualTo("Alpha");
        assertThat(card.message()).isEqualTo("Bonjour c'est toto");
    }

    @Test
    @DisplayName("clicking a card brings its receiver to the foreground, and takes the card down")
    void clickingACardFocusesTheReceiver() throws InterruptedException {
        final var toaster = toaster();
        // A whisper for Alpha while the player is on Bravo, so the focus has somewhere to move.
        toaster.onNotification(whisper("Alpha", "Zoro", "coucou"));
        settle();
        final var id = view.cards().getFirst().id();

        view.clickCard(id);
        settle();

        assertThat(api.foregroundWindow()).as("Alpha came up to be answered").isEqualTo(1);
        assertThat(view.isVisible()).as("the read card is gone, and nothing is left to show").isFalse();
    }

    @Test
    @DisplayName("the close cross takes a card down")
    void theCrossDismissesACard() throws InterruptedException {
        final var toaster = toaster();
        toaster.onNotification(whisper("Bravo", "Alpha", "Bonjour"));
        settle();
        assertThat(view.isVisible()).isTrue();
        final var id = view.cards().getFirst().id();

        view.clickClose(id);
        settle();

        assertThat(view.isVisible()).as("dismissed, and no other whisper to stand in its place").isFalse();
    }

    @Test
    @DisplayName("a second whisper stacks on the first, oldest first")
    void asecondWhisperStacks() throws InterruptedException {
        final var toaster = toaster();

        toaster.onNotification(whisper("Bravo", "Alpha", "on go ?"));
        settle();
        assertThat(view.cards()).hasSize(1);

        toaster.onNotification(whisper("Bravo", "Charlie", "j'arrive"));
        settle();

        assertThat(view.cards())
                .as("both are up, in the order they arrived — the newest sits at the bottom of the stack")
                .extracting(ToastContent.Card::sender)
                .containsExactly("Alpha", "Charlie");
    }

    @Test
    @DisplayName("a card fades on its own once its lifetime is up")
    void aCardFadesAfterItsLifetime() throws InterruptedException {
        final var toaster = toaster(Duration.ofMillis(200));

        toaster.onNotification(whisper("Bravo", "Alpha", "Bonjour"));
        Thread.sleep(90); // well inside the lifetime
        assertThat(view.isVisible()).as("the card is up while it is fresh").isTrue();

        Thread.sleep(500); // well past it
        assertThat(view.isVisible()).as("and gone once it has aged out").isFalse();
    }

    @Test
    @DisplayName("a game toast that is not a whisper raises no card")
    void ignoresNonWhisperToasts() throws InterruptedException {
        final var toaster = toaster();

        toaster.onNotification(new Notification("Bravo - Dofus Retro", "Vous êtes attaqué !"));
        settle();

        assertThat(view.isVisible()).isFalse();
        assertThat(view.timesDrawn()).isZero();
    }

    @Test
    @DisplayName("the whisper outlives its card, and under the same id the card was raised with")
    void theWhisperOutlivesItsCard() throws InterruptedException {
        final var toaster = toaster(Duration.ofMillis(200));

        toaster.onNotification(whisper("Bravo", "Alpha", "Bonjour c'est toto"));
        settle();
        final var drawn = view.content().orElseThrow().cards().getFirst();

        Thread.sleep(500); // well past the card's lifetime
        assertThat(view.isVisible()).as("the card has faded").isFalse();

        assertThat(whispers.recent())
                .as("but the panel can still list it, which is the point of keeping it")
                .extracting(Whisper::sender, Whisper::message)
                .containsExactly(tuple("Alpha", "Bonjour c'est toto"));
        assertThat(whispers.find(drawn.id()))
                .as("under the very id the card carried, so a click in the panel finds it")
                .isPresent();
    }

    @Test
    @DisplayName("a toast that is not a whisper is not remembered either")
    void doesNotRememberNonWhispers() throws InterruptedException {
        final var toaster = toaster();

        toaster.onNotification(new Notification("Bravo - Dofus Retro", "Vous êtes attaqué !"));
        settle();

        assertThat(whispers.recent()).isEmpty();
    }
}
