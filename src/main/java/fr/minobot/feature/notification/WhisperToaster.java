package fr.minobot.feature.notification;

import fr.minobot.app.Settings;
import fr.minobot.core.FocusManager;
import fr.minobot.core.NotificationManager;
import fr.minobot.core.WhisperLog;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.core.domain.Notification;
import fr.minobot.core.domain.Whisper;
import fr.minobot.ui.ToastActions;
import fr.minobot.ui.ToastContent;
import fr.minobot.ui.ToastView;
import fr.minobot.win32.Rect;
import fr.minobot.win32.WindowApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Turns a private message into a quiet toast instead of a jump.
 *
 * <p>The game raises a Windows toast whenever a background character is whispered — the one game event
 * still heard from the toasts rather than off the screen. Pulling the whole screen to the receiver would
 * be too much for one line of chat, so this recognises a whisper by the shape of its message and shows a
 * small card at the left edge of the game — who was whispered, by whom, and what they said. The card lives for {@link #TOAST_LIFETIME}, a close cross takes it down early, and
 * a click on it brings the receiver up so the player can answer. Several whispers stack.
 *
 * <p><strong>The card fades; the whisper does not.</strong> Every one it raises is first written to the
 * {@link WhisperLog}, and the card is raised under the id the log minted — so the panel can list the same
 * whisper long after its ten seconds are up, and a click there is the same jump the card offered. The two
 * lifetimes are the reason the log is not a field of this class: a card is a thing on screen, a whisper is
 * a thing that happened.
 *
 * <p>It is the mirror of {@link ExchangeAccepter} and {@link TurnPasser}: a feature that registers with
 * {@link NotificationManager}, speaks of <strong>characters</strong> ({@link GameWindow#name()}), and
 * keeps the Windows detail — the French wording the message is matched by — in one named constant. Its
 * one novelty is a UI surface, cut on the overlay's interface/impl seam ({@link ToastView} and
 * {@link ToastActions}) so all the deciding stays testable with no screen.
 *
 * <p>Like the panel it <em>follows</em>: a single virtual thread re-reads the foreground game window and
 * keeps the stack pinned to it, or takes it away while the player is out of the game. The follow loop is
 * also where a toast dies of old age — one poll evicts what has expired, so there is no timer thread per
 * card. It runs only while there is something to show, and virtual threads are daemons, so a toast still
 * alive at shutdown holds nothing up.
 */
public final class WhisperToaster implements ToastActions {

    private static final Logger log = LoggerFactory.getLogger(WhisperToaster.class);

    /** How long a whisper card stands before it fades on its own. Long enough to read, short enough to forget. */
    static final Duration TOAST_LIFETIME = Duration.ofSeconds(10);

    /** How often the stack checks it is still on the foreground game window, and prunes what has expired. */
    private static final Duration FOLLOW_INTERVAL = Duration.ofMillis(30);

    /**
     * What a whisper toast's message reads: {@code "de Alpha : Bonjour c'est toto"} — the sender, then
     * the line. The French wording is the game's, so it is a constant here and not a setting. A message
     * that does not match is not a whisper: the feature leaves it, and the auto-focus keeps it.
     */
    private static final Pattern WHISPER = Pattern.compile("^\\s*de\\s+(\\S+)\\s*:\\s*(.*)$");

    private final WindowApi api;
    private final WindowManager windows;
    private final FocusManager focus;
    private final Settings settings;
    private final WhisperLog whispers;
    private final Duration lifetime;
    private final ToastView view;

    /** The whispers still on screen, oldest first. Snapshot-iterated, so the follow loop reads it freely. */
    private final CopyOnWriteArrayList<ActiveToast> active = new CopyOnWriteArrayList<>();

    /** Whether the follow loop is running — one at a time, whichever whisper started it. */
    private final AtomicBoolean following = new AtomicBoolean();

    public WhisperToaster(WindowApi api, WindowManager windows, FocusManager focus, Settings settings,
                          NotificationManager notifications, WhisperLog whispers,
                          Function<ToastActions, ToastView> viewFactory) {
        this(api, windows, focus, settings, notifications, whispers, TOAST_LIFETIME, viewFactory);
    }

    /** @param lifetime how long each card stands — the shorter one the tests hand it, or the default */
    public WhisperToaster(WindowApi api, WindowManager windows, FocusManager focus, Settings settings,
                          NotificationManager notifications, WhisperLog whispers, Duration lifetime,
                          Function<ToastActions, ToastView> viewFactory) {
        this.api = api;
        this.windows = windows;
        this.focus = focus;
        this.settings = settings;
        this.whispers = whispers;
        this.lifetime = lifetime;
        this.view = viewFactory.apply(this);

        notifications.register(this::onNotification);
    }

    /** Runs on a virtual thread, one per notification, from {@link NotificationManager}. */
    void onNotification(Notification notification) {
        final var parsed = parse(notification);
        if (parsed.isEmpty()) {
            return; // not a whisper: the auto-focus keeps it
        }

        // Remembered first, and the card is raised under the id the log minted: the card and the row
        // the panel will show once it has faded are then the same whisper, and a click on either finds it.
        final var whisper = whispers.add(parsed.get().receiver(), parsed.get().sender(),
                parsed.get().message());
        active.add(new ActiveToast(whisper, System.nanoTime() + lifetime.toNanos()));
        log.info("Whisper for '{}' from '{}'.", whisper.receiver(), whisper.sender());
        startFollowing();
    }

    @Override
    public void open(String id) {
        final var toast = find(id);
        if (toast.isEmpty()) {
            return; // it faded, or was already dismissed, between the draw and the click
        }

        final var receiver = toast.get().whisper().receiver();
        active.removeIf(t -> t.id().equals(id)); // read; the card now sits over the very window it named

        // The focus sequence sleeps through its ALT dance: never on the event dispatch thread the click
        // arrives on, or the whole stack would freeze while the window comes up.
        Thread.ofVirtual().name("whisper-open").start(() ->
                windows.findWindow(receiver).ifPresentOrElse(
                        window -> focus.focus(window.hwnd()),
                        () -> log.warn("Clicked a whisper for '{}', but no window was found for them.", receiver)));
    }

    @Override
    public void dismiss(String id) {
        active.removeIf(t -> t.id().equals(id));
    }

    /**
     * The receiver, sender and line a whisper toast carries, or empty when the toast is not a whisper.
     *
     * <p>The receiver is in the title, as every game toast is written; the sender and the line are in the
     * message, in the shape {@link #WHISPER} matches. A game toast whose message is anything else — an
     * invitation, a turn, a trade — does not match, and is none of this feature's business.
     */
    private Optional<Parsed> parse(Notification notification) {
        final var title = notification.title();
        if (title == null || title.isBlank() || !WindowManager.isGameTitle(title)) {
            return Optional.empty();
        }

        final var message = notification.message();
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }

        final var matcher = WHISPER.matcher(message);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        return Optional.of(new Parsed(GameWindow.nameIn(title), matcher.group(1), matcher.group(2).strip()));
    }

    private Optional<ActiveToast> find(String id) {
        return active.stream().filter(toast -> toast.id().equals(id)).findFirst();
    }

    private void startFollowing() {
        if (following.compareAndSet(false, true)) {
            Thread.ofVirtual().name("whisper-follow").start(this::follow);
        }
    }

    /**
     * Keeps the stack pinned to the foreground game window, and prunes what has faded.
     *
     * <p>A thread of its own, exactly as the panel's follower is, and for the same reason: Windows will
     * not say a window moved without a message hook. It also does the ageing — one poll drops every
     * expired card — so a card needs no timer of its own. When nothing is left, it takes the stack down
     * and stops, rather than polling an empty screen forever.
     */
    private void follow() {
        ToastContent lastContent = null;
        Rect lastAnchor = null;
        try {
            while (true) {
                active.removeIf(ActiveToast::expired);
                if (active.isEmpty()) {
                    if (view.isVisible()) {
                        view.hide();
                    }
                    return;
                }

                final var foreground = windows.foregroundGameWindow();
                final var area = foreground.flatMap(window -> onScreen(window.hwnd()));

                if (area.isEmpty()) {
                    // The player is out of the game, or the window is minimized: the stack waits offscreen,
                    // and the cards go on ageing so a whisper is not still waiting when the player returns.
                    if (view.isVisible()) {
                        view.hide();
                        lastContent = null;
                        lastAnchor = null;
                    }
                } else {
                    final var content = content();
                    if (!view.isVisible() || !content.equals(lastContent)) {
                        view.show(content, area.get());
                    } else if (!area.get().equals(lastAnchor)) {
                        view.moveTo(area.get());
                    }
                    lastContent = content;
                    lastAnchor = area.get();
                }

                if (!sleep()) {
                    return;
                }
            }
        } finally {
            following.set(false);
            // A whisper may have landed between the empty-check and here: do not strand it. startFollowing
            // uses a CAS, so if that whisper already restarted the loop this is a no-op — never two loops.
            if (!active.isEmpty()) {
                startFollowing();
            }
        }
    }

    /** The window's client area if it is on screen to hang the stack on — empty when it is minimized. */
    private Optional<Rect> onScreen(long hwnd) {
        return api.isIconic(hwnd) ? Optional.empty() : api.clientArea(hwnd);
    }

    /** What the stack shows, read fresh: the live cards, at the scale the panel is drawn at. */
    private ToastContent content() {
        final var cards = active.stream()
                .map(ActiveToast::whisper)
                .map(whisper -> new ToastContent.Card(whisper.id(), whisper.receiver(),
                        whisper.sender(), whisper.message()))
                .toList();
        final var config = settings.get();
        return new ToastContent(config.overlayScale(), config.language(), cards);
    }

    /** @return whether the follower may go on; {@code false} means the thread was interrupted */
    private static boolean sleep() {
        try {
            Thread.sleep(FOLLOW_INTERVAL);
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** A whisper on screen, and the instant its card is due to fade — the whisper itself outlives it. */
    private record ActiveToast(Whisper whisper, long deadlineNanos) {

        String id() {
            return whisper.id();
        }

        boolean expired() {
            return System.nanoTime() >= deadlineNanos;
        }
    }

    /** A toast read as a whisper: who it was for, who sent it, what it said. Not yet remembered. */
    private record Parsed(String receiver, String sender, String message) {
    }
}
