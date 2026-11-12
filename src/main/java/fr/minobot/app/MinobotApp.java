package fr.minobot.app;

import fr.minobot.core.*;
import fr.minobot.core.KeyboardMonitor.Binding;
import fr.minobot.core.input.InputSimulator;
import fr.minobot.core.vision.ChatLog;
import fr.minobot.core.vision.RememberingTextReader;
import fr.minobot.core.vision.RequestDialog;
import fr.minobot.core.vision.ScreenReader;
import fr.minobot.core.vision.ScreenWatcher;
import fr.minobot.core.vision.SpellBars;
import fr.minobot.core.vision.TurnBanner;
import fr.minobot.feature.econome.SpellMarks;
import fr.minobot.feature.group.GroupManager;
import fr.minobot.feature.notification.AutoPassBanner;
import fr.minobot.feature.notification.ExchangeAccepter;
import fr.minobot.feature.notification.NotificationListener;
import fr.minobot.feature.notification.RequestFocuser;
import fr.minobot.feature.notification.RequestToasts;
import fr.minobot.feature.notification.TurnFocuser;
import fr.minobot.feature.notification.TurnPasser;
import fr.minobot.feature.notification.TurnToasts;
import fr.minobot.feature.notification.WhisperToaster;
import fr.minobot.feature.overlay.OverlayController;
import fr.minobot.feature.window.MultiWindowClicker;
import fr.minobot.feature.window.WindowCycler;
import fr.minobot.feature.window.WindowReorder;
import fr.minobot.ui.banner.SwingBanner;
import fr.minobot.ui.marks.SwingMarks;
import fr.minobot.ui.overlay.SwingOverlay;
import fr.minobot.ui.toast.SwingToastStack;
import fr.minobot.win32.User32;
import fr.minobot.win32.WindowApi;
import fr.minobot.win32.WindowsOcr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates the components and the features — the counterpart of {@code app.py}.
 *
 * <p>The features hold no state of their own beyond a running flag: they are wired here, onto the
 * hotkeys of the {@link KeyboardMonitor} and the callbacks of the {@link NotificationManager}, and
 * everything they touch goes through the core.
 */
public final class MinobotApp {

    private static final Logger log = LoggerFactory.getLogger(MinobotApp.class);

    private final Settings settings;

    private final SystemTrayManager systemTray;
    private final WindowManager windowManager;
    private final InputSimulator inputSimulator;
    private final KeyboardMonitor keyboardMonitor;
    private final FocusManager focusManager;
    private final NotificationManager notificationManager;
    private final FlashSuppressor flashSuppressor;

    /**
     * Empty when Windows has no OCR language for this user: the turns and the requests are then heard
     * of by nobody.
     */
    private final Optional<WindowsOcr> ocr;
    private final Optional<ScreenWatcher> screenWatcher;

    /**
     * What every feature writes to and the panel reads back — the two records of what happened while the
     * player was looking elsewhere. One instance each, shared: a feature with a log of its own would be a
     * feature the panel cannot show.
     */
    private final ActivityLog activityLog;
    private final WhisperLog whisperLog;

    private final MultiWindowClicker multiClicker;
    private final WindowCycler windowCycler;
    private final WindowReorder windowReorder;
    private final GroupManager groupManager;
    private final OverlayController overlay;

    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean stopping = new AtomicBoolean();

    public MinobotApp() {
        this(User32.instance());
    }

    public MinobotApp(WindowApi api) {
        final var baseDirectory = ConfigLoader.baseDirectory();
        final var configPath = baseDirectory.resolve("config.json");
        final var overlayPath = baseDirectory.resolve("overlay.json");

        this.settings = new Settings(ConfigLoader.load(configPath, overlayPath));
        LoggerSetup.configure(settings.get(), baseDirectory);

        log.info("=== Minobot starting (app dir: {}) ===", baseDirectory);
        log.info("Using config: {}", configPath);

        this.activityLog = new ActivityLog();
        this.whisperLog = new WhisperLog();

        this.systemTray = new SystemTrayManager(this::stop);
        this.windowManager = new WindowManager(api, settings, activityLog);
        this.inputSimulator = new InputSimulator();
        this.keyboardMonitor = new KeyboardMonitor(api);
        this.focusManager = new FocusManager(api, inputSimulator);
        this.notificationManager = new NotificationManager();
        this.flashSuppressor = new FlashSuppressor(api);

        // Every turn of a fight and every request made of a character, heard of once and from the screen
        // alone: the game's toast goes deaf on a window the multi-click has posted to, the screen does not.
        final var fightTurns = new FightTurns();
        final var requests = new Requests();

        // What each character cast this fight, read off the fight log in the chat of the first character —
        // at every look, since a spell that hits five fighters scrolls its line away within the second —
        // and whether the spell bar of the character the player is on is there to be marked.
        final var fightLog = new FightLog();
        final var spellUsage = new SpellUsage(fightLog, fightTurns);
        final var spellBars = new SpellBars();

        this.ocr = WindowsOcr.start();
        if (ocr.isEmpty()) {
            log.warn("No Windows OCR language for this user: turns, requests and casts will not be heard.");
        }
        this.screenWatcher = ocr.map(reader -> new ScreenWatcher(api, windowManager, List.of(
                        ScreenReader.turns(new TurnBanner(new RememberingTextReader(reader)), fightTurns),
                        ScreenReader.requests(new RequestDialog(new RememberingTextReader(reader)), requests)),
                List.of(new ScreenWatcher.Pinned(characters -> characters.stream().findFirst(),
                                ScreenReader.fightLog(new ChatLog(reader), fightLog, fightTurns::inFight)),
                        new ScreenWatcher.Pinned(characters -> characters.stream()
                                .filter(window -> window.hwnd() == api.foregroundWindow()).findFirst(),
                                ScreenReader.spellBar(spellBars))),
                fightTurns::inFight, requests::awaited));

        this.multiClicker = new MultiWindowClicker(
                api, windowManager, focusManager, flashSuppressor, settings, activityLog);
        this.windowCycler = new WindowCycler(api, windowManager, focusManager, activityLog);
        this.windowReorder = new WindowReorder(api, windowManager, focusManager, activityLog);
        this.groupManager = new GroupManager(
                windowManager, inputSimulator, focusManager, requests, activityLog);
        this.overlay = new OverlayController(api, windowManager, settings, keyboardMonitor, focusManager,
                activityLog, whisperLog, spellUsage, SwingOverlay::new);

        // Crosses out the spells each character cast this fight, for the Économe challenge, and learns
        // which slot holds which spell while the player sets the bar up. Both are switches on the panel.
        new SpellMarks(api, windowManager, settings, fightTurns, fightLog, spellUsage, spellBars,
                new SpellLayouts(baseDirectory.resolve("spell-layouts.json")), activityLog, overlay::isShowing,
                SwingMarks::new);

        // Answers a trade between two of the player's own characters in place. Built before the
        // request auto-focus so it can consult it and stand aside for the trades it takes.
        final var exchangeAccepter = new ExchangeAccepter(api, windowManager, inputSimulator,
                focusManager, requests, settings, activityLog);

        // Shows a whisper as a quiet toast at the left of the game rather than pulling the screen to it,
        // and writes it to the whisper log so the panel can still list it once the card has faded. Also
        // built before the auto-focus, which stands aside for the whispers it takes.
        final var whisperToaster = new WhisperToaster(api, windowManager, focusManager, settings,
                notificationManager, whisperLog, SwingToastStack::new);

        // Registers itself with the notification manager; it has no hotkey of its own. It stands aside
        // for a toast answered elsewhere — a whisper, a turn, a request — and takes the player
        // everywhere else. The predicate is the OR of those features' claims.
        new NotificationListener(windowManager, focusManager, notificationManager, activityLog,
                notification -> whisperToaster.claims(notification) || TurnToasts.claims(notification)
                        || RequestToasts.claims(notification));

        // A request toast reports nothing, but it comes at once: it points the screen at the window it names.
        new RequestToasts(windowManager, notificationManager, requests);

        // Takes the player to the character something is asked of, unless it is answered in place.
        new RequestFocuser(windowManager, focusManager, requests, activityLog, exchangeAccepter::claims);

        // Takes the player to the character whose turn it is, read off the banner on the screen.
        new TurnFocuser(windowManager, focusManager, fightTurns, activityLog);

        // Ends each character's turn while the Auto-pass switch is on, reading it at every turn. The
        // switch is flipped by the overlay or by the AUTO_PASS_TURN hotkey (bound below like any other).
        new TurnPasser(windowManager, inputSimulator, focusManager, fightTurns, settings, activityLog);

        // The visible half of the same switch: a standing banner over the game while auto-pass is on. It
        // answers no toast — it watches the switch through Settings.onChange and follows the game window.
        new AutoPassBanner(api, windowManager, settings, SwingBanner::new);

        // Every change goes through the whole set again: it is one swap in the monitor, and it spares
        // us the question of which field the overlay touched.
        settings.onChange(this::bindHotkeys);
        bindHotkeys(settings.get());

        // The character order, the keybinds and each character's class outlive the session: one more
        // listener writes them to overlay.json. It fires on every change but persists only that subset,
        // so the scale and the two switches stay session-only — deliberately, for the switches. Settings
        // itself still writes nothing; persistence is an observer, exactly like the rebinding above.
        settings.onChange(config -> OverlayState.save(overlayPath, config));
    }

    /**
     * Binds every feature to the key it currently has. A blank hotkey leaves the feature unbound, which
     * is how a player turns it off — and how the overlay will.
     */
    private void bindHotkeys(Config config) {
        final var bindings = new ArrayList<Binding>();

        for (final var feature : Feature.values()) {
            final var hotkey = feature.hotkeyIn(config);
            if (hotkey.isBlank()) {
                log.info("Feature '{}' is disabled: no hotkey.", feature.label());
                continue;
            }

            final var cooldown = feature.cooldown();
            bindings.add(switch (feature) {
                case MULTICLICK ->
                        Binding.withCursor(hotkey, cooldown, multiClicker::clickEveryCharacter);
                case RESET_WINDOWS -> Binding.of(hotkey, cooldown, multiClicker::resetCharacters);
                case GROUP_INVITE -> Binding.of(hotkey, cooldown, groupManager::inviteAll);
                case WINDOW_CYCLE_NEXT -> Binding.of(hotkey, cooldown, windowCycler::cycleNext);
                case WINDOW_CYCLE_PREV -> Binding.of(hotkey, cooldown, windowCycler::cyclePrev);
                case WINDOW_REORDER -> Binding.of(hotkey, cooldown, windowReorder::reorderTaskbar);
                case OVERLAY -> Binding.of(hotkey, cooldown, overlay::toggle);
                case AUTO_PASS_TURN -> Binding.of(hotkey, cooldown, overlay::flipAutoPassTurn);
            });
            log.debug("Feature '{}' enabled on hotkey '{}'.", feature.label(), hotkey);
        }

        keyboardMonitor.rebind(bindings);
    }

    /** The live configuration: what the overlay reads, and the only thing allowed to change it. */
    public Settings settings() {
        return settings;
    }

    public WindowManager windowManager() {
        return windowManager;
    }

    public FocusManager focusManager() {
        return focusManager;
    }

    public InputSimulator inputSimulator() {
        return inputSimulator;
    }

    public KeyboardMonitor keyboardMonitor() {
        return keyboardMonitor;
    }

    public NotificationManager notificationManager() {
        return notificationManager;
    }

    /** Starts every service, then blocks until {@link #stop()} is called. */
    public void run() throws InterruptedException {
        systemTray.start();
        windowManager.refresh();

        // A session-wide setting, and the only thing that keeps the multi-clicked windows from
        // flashing in the taskbar. Given back in stop().
        flashSuppressor.suppress();

        notificationManager.start();
        screenWatcher.ifPresent(ScreenWatcher::start);
        keyboardMonitor.start();

        log.info("=== Application services ready ===");

        stopped.await();
    }

    /** Idempotent: the tray's Quit item and the JVM shutdown hook both land here. */
    public void stop() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }

        log.info("=== Minobot stopping ===");
        keyboardMonitor.stop();
        notificationManager.stop();
        screenWatcher.ifPresent(ScreenWatcher::stop);
        ocr.ifPresent(WindowsOcr::close);
        flashSuppressor.restore();
        systemTray.stop();

        stopped.countDown();
    }
}
