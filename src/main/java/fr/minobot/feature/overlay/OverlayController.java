package fr.minobot.feature.overlay;

import fr.minobot.app.Feature;
import fr.minobot.app.Settings;
import fr.minobot.core.ActivityLog;
import fr.minobot.core.FocusManager;
import fr.minobot.core.KeyboardMonitor;
import fr.minobot.core.WhisperLog;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.Character;
import fr.minobot.core.domain.DofusClass;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.core.domain.Sex;
import fr.minobot.ui.CharacterEntry;
import fr.minobot.ui.OverlayActions;
import fr.minobot.ui.OverlayContent;
import fr.minobot.ui.OverlayView;
import fr.minobot.win32.Rect;
import fr.minobot.win32.WindowApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * The control panel: the characters Minobot has found, the order they are cycled in, and the key each
 * feature answers to — all of it editable, on top of the game.
 *
 * <p><strong>A reorder, a rebind, a class and a sex are kept; the rest is not.</strong> The character
 * order, the keybinds and each character's class and sex go through {@link Settings} and are persisted to
 * {@code overlay.json} (see {@link fr.minobot.app.OverlayState}), so they survive a restart. The scale
 * and the two switches also go
 * through {@link Settings}, but nothing persists them: they live for the session only, the switches
 * deliberately — an {@code Auto-pass} found already on after a restart would end turns nobody asked it
 * to. The controller does not know which of the two happens; it just calls {@code settings.update}.
 *
 * <p>The panel <em>belongs to a character</em>. It covers their game — the client area exactly, never
 * the title bar Windows draws above it — and it <em>follows</em> it: move the window, resize it, and
 * the panel goes with it. Pressing the hotkey anywhere but on a game window does nothing at all. Not
 * "shown somewhere by default": outside the game there is no character for it to belong to, and a panel
 * that appears over a window the player was not looking at is a panel they did not ask for.
 *
 * <p><strong>It never has to be told to look again.</strong> The desktop is re-read when the panel opens
 * and every couple of seconds while it is up, and the panel is redrawn only when what came back differs
 * from what is on the screen. That is why there is no {@code Reload} button: the one thing it did, the
 * panel now does at the two moments the player would have thought to press it.
 *
 * <p>Once up it <em>stays</em> up, whatever takes the foreground next: a toast pulling the focus to
 * another character, the player clicking into another application. The panel is always-on-top, and
 * always-on-top owes nothing to the focus. The hotkey is the only switch — except that a character who
 * leaves takes their panel with them: minimized, or closed, and it goes.
 *
 * <p>It never takes the foreground itself — see {@code OverlayView}. It must not: {@link
 * fr.minobot.core.FocusManager} is what keeps the features from fighting over the one screen, and a
 * panel that stole the focus would land in the middle of the invitation relay's keystrokes.
 */
public final class OverlayController implements OverlayActions {

    private static final Logger log = LoggerFactory.getLogger(OverlayController.class);

    /** Long enough for a player to reach for the key they meant, short enough to give up on its own. */
    private static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(5);

    /**
     * How often the panel checks that it is still on top of its character.
     *
     * <p>Fast enough that a dragged window does not visibly outrun it, and one native call is all it
     * costs — the same call the multi-click makes several of, several times a second.
     */
    private static final Duration FOLLOW_INTERVAL = Duration.ofMillis(30);

    /**
     * How often the panel goes back to the desktop for the roster while it is up.
     *
     * <p>Far slower than the follow above, because it is a far heavier question: where a window sits is
     * one native call, while the roster is the whole desktop enumerated and every title read. Slow enough
     * that it costs nothing next to the game it is drawn over, quick enough that a character who has just
     * finished loading is on the list before the player thinks to look for them.
     */
    private static final Duration ROSTER_INTERVAL = Duration.ofSeconds(2);

    private final WindowApi api;
    private final WindowManager windows;
    private final Settings settings;
    private final KeyboardMonitor keyboard;
    private final FocusManager focus;
    private final ActivityLog activity;
    private final WhisperLog whispers;
    private final OverlayView view;

    /** Where the panel last was, so an edit can redraw it without asking Windows again. */
    private final AtomicReference<Rect> bounds = new AtomicReference<>();

    /**
     * What the panel was last handed to draw, so the roster poll can tell a change from a redraw for
     * nothing. Written by the drawing thread, read by the follower.
     */
    private final AtomicReference<OverlayContent> shown = new AtomicReference<>();

    /**
     * @param viewFactory hands the view its way back in, so neither has to be built before the other
     */
    public OverlayController(WindowApi api, WindowManager windows, Settings settings,
                             KeyboardMonitor keyboard, FocusManager focus,
                             ActivityLog activity, WhisperLog whispers,
                             Function<OverlayActions, OverlayView> viewFactory) {
        this.api = api;
        this.windows = windows;
        this.settings = settings;
        this.keyboard = keyboard;
        this.focus = focus;
        this.activity = activity;
        this.whispers = whispers;
        this.view = viewFactory.apply(this);
    }

    /** Whether the panel is up — over the whole of a game, where nothing else should be drawn. */
    public boolean isShowing() {
        return view.isVisible();
    }

    /** Shows the panel over the character the player is on, or takes it away if it is already up. */
    public void toggle() {
        if (view.isVisible()) {
            log.debug("Hiding the overlay.");
            view.hide();
            return;
        }

        final var character = characterInForeground();
        if (character.isEmpty()) {
            log.debug("The overlay hotkey was pressed outside the game: nothing to show it on.");
            return;
        }

        final var area = api.clientArea(character.get().hwnd());
        if (area.isEmpty()) {
            log.warn("'{}' went away before the overlay could be placed on it.", character.get().name());
            return;
        }

        // The desktop as it is now, not as the thirty-second sweep last left it. The player is opening a
        // list they are about to read, and a character who logged in a moment ago belongs on it: this is
        // the refresh the Reload button used to ask for, spent where nobody has to press anything.
        windows.refresh();

        log.info("Showing the overlay on '{}'.", character.get().name());
        bounds.set(area.get());
        draw(content(), area.get());

        follow(character.get());
    }

    /**
     * Keeps the panel on its character — and its list on the desktop — while it is up.
     *
     * <p>A thread of its own, because there is nothing to react to: Windows will not tell us a window
     * moved unless we hook its messages, and hooking them would mean a message pump and a native
     * callback for something one poll answers. It lives exactly as long as the panel does, and it asks
     * two questions at two rhythms — where the window is, thirty times a second, and who is logged in,
     * every couple of seconds.
     */
    private void follow(GameWindow character) {
        Thread.ofVirtual().name("overlay-follow").start(() -> {
            var lastRoster = System.nanoTime();

            while (view.isVisible()) {
                final var area = api.clientArea(character.hwnd());

                // The character has gone: minimized, or closed. Their panel goes with them — leaving it
                // floating over the game they are no longer playing would be a panel nobody can dismiss.
                if (area.isEmpty() || api.isIconic(character.hwnd())) {
                    log.debug("'{}' is no longer on screen: taking the overlay down.", character.name());
                    view.hide();
                    return;
                }

                if (!area.get().equals(bounds.get())) {
                    bounds.set(area.get());
                    view.moveTo(area.get());
                }

                if (System.nanoTime() - lastRoster >= ROSTER_INTERVAL.toNanos()) {
                    lastRoster = System.nanoTime();
                    refreshRoster();
                }

                if (!sleep()) {
                    return;
                }
            }
        });
    }

    /**
     * Goes back to the desktop for the roster, and redraws only if it came back different.
     *
     * <p>This is what the panel has instead of a Reload button: a character who logs in or out while it is
     * open takes their place, or greys out, on their own — and so does a line the console has just gained.
     * The comparison is the whole point. The panel is <em>rebuilt</em> at every draw, not patched, so a
     * poll that redrew unconditionally would drop the row the player has under the pointer twice a second.
     */
    private void refreshRoster() {
        windows.refresh();

        final var fresh = content();
        final var placed = bounds.get();
        if (placed != null && !fresh.equals(shown.get())) {
            log.debug("The desktop changed under the overlay: redrawing it.");
            draw(fresh, placed);
        }
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

    @Override
    public void reorder(List<String> characters) {
        // A not-yet-logged-in window rides in the list under its label, but has no name to be cycled by:
        // it must not land in the order, where it would be a dead entry until the character loads.
        final var named = characters.stream()
                .filter(name -> !name.equals(OverlayContent.LOGGING_IN))
                .toList();
        log.info("New character order: {}", named);
        settings.update(config -> config.withCharacterOrder(named));
        redraw();
    }

    @Override
    public void rebind(Feature feature, String combination) {
        if (combination.isBlank()) {
            log.info("Feature '{}' turned off.", feature.label());
        } else {
            log.info("Feature '{}' rebound to '{}'.", feature.label(), combination);
        }

        settings.update(config -> config.withHotkey(feature, combination));
        redraw();
    }

    @Override
    public void assignClass(String character, DofusClass clazz) {
        // The login placeholder is not a character: it has no name to key a class by, and the view does
        // not offer it one — but a stale click racing a disconnect could still arrive, so it is refused
        // here too rather than persisting a class for a window nobody is playing.
        if (character.equals(OverlayContent.LOGGING_IN)) {
            return;
        }

        log.info("'{}' set to {}.", character, clazz.label());
        settings.update(config -> config.withCharacterClass(character, clazz));
        redraw();
    }

    @Override
    public void assignSex(String character, Sex sex) {
        if (character.equals(OverlayContent.LOGGING_IN)) {
            return;
        }

        log.info("'{}' set to {}.", character, sex);
        settings.update(config -> config.withCharacterSex(character, sex));
        redraw();
    }

    @Override
    public void forget(String character) {
        // The login placeholder is not a saved character; and the view offers the cross on disconnected,
        // pinned rows only. A stale click racing something else lands here as a no-op rather than a wrong
        // deletion.
        if (character.equals(OverlayContent.LOGGING_IN)) {
            return;
        }

        log.info("Forgetting '{}'.", character);
        settings.update(config -> config.withoutCharacter(character));
        redraw();
    }

    @Override
    public void rescale(double scale) {
        log.info("Overlay scale set to {}%.", Math.round(scale * 100));
        settings.update(config -> config.withOverlayScale(scale));
        redraw();
    }

    @Override
    public void toggleAutoPassTurn(boolean on) {
        log.info("Auto-pass turns switched {}.", on ? "on" : "off");
        settings.update(config -> config.withAutoPassTurn(on));
        redraw();
    }

    /**
     * Flips the turn-passer on or off — the hotkey's way in, the twin of the panel's switch. The negation
     * is inside the update so two presses in flight cannot both read the same "off" and cancel out; the
     * panel, if it is open, redraws to show the switch in its new state.
     */
    public void flipAutoPassTurn() {
        settings.update(config -> config.withAutoPassTurn(!config.autoPassTurn()));
        log.info("Auto-pass turns toggled to {}.", settings.get().autoPassTurn() ? "on" : "off");
        redraw();
    }

    @Override
    public void toggleAutoAcceptTrade(boolean on) {
        log.info("Auto-accept trades switched {}.", on ? "on" : "off");
        settings.update(config -> config.withAutoAcceptTrade(on));
        redraw();
    }

    @Override
    public void toggleEconome(boolean on) {
        log.info("Easyconome switched {}.", on ? "on" : "off");
        settings.update(config -> config.withEconome(on));
        redraw();
    }

    @Override
    public void toggleSpellSetup(boolean on) {
        log.info("Spell setup switched {}.", on ? "on" : "off");
        settings.update(config -> config.withSpellSetup(on));
        redraw();
    }

    /**
     * Takes the player to the character a whisper was sent to.
     *
     * <p>The panel goes down first: the player asked to go and answer, and the panel covers the whole
     * client area of the window they are being sent to. The focus sequence sleeps through its ALT dance,
     * so it runs on a thread of its own — never on the event dispatch thread the click arrived on.
     */
    @Override
    public void openWhisper(String whisper) {
        final var found = whispers.find(whisper);
        if (found.isEmpty()) {
            return; // cleared between the draw and the click
        }

        final var receiver = found.get().receiver();
        view.hide();

        Thread.ofVirtual().name("overlay-whisper").start(() ->
                windows.findWindow(receiver).ifPresentOrElse(
                        window -> focus.focus(window.hwnd()),
                        () -> log.warn("Clicked a whisper for '{}', but no window was found for them.",
                                receiver)));
    }

    @Override
    public void clearWhispers() {
        whispers.clear();
        redraw();
    }

    @Override
    public Optional<String> captureHotkey() {
        return keyboard.captureNext(CAPTURE_TIMEOUT);
    }

    /**
     * The character whose window holds the screen right now, if it is one of ours.
     *
     * <p>Decided from the foreground window's own process, not from the tracked list: a login window
     * the 30-second sweep has not caught is still the game, and the panel must open on it. Empty for
     * anything else — a browser, the desktop. It may carry no character name yet (a login screen); the
     * panel handles that with its {@link #LOGGING_IN} label.
     */
    private Optional<GameWindow> characterInForeground() {
        return windows.foregroundGameWindow();
    }

    /** What the panel shows, read fresh from the live configuration and the windows on screen. */
    private OverlayContent content() {
        final var config = settings.get();

        final var hotkeys = new EnumMap<Feature, String>(Feature.class);
        for (final var feature : Feature.values()) {
            hotkeys.put(feature, feature.hotkeyIn(config));
        }

        // The windows on screen right now, by the character played in each — so a saved character can be
        // matched to its live window, and a login screen (which names no character yet) is noted apart.
        final var liveByName = new LinkedHashMap<String, GameWindow>();
        var loginOnScreen = false;
        for (final var window : windows.orderedWindows()) {
            if (window.hasCharacterName()) {
                liveByName.put(window.name(), window);
            } else {
                loginOnScreen = true;
            }
        }

        final var entries = new ArrayList<CharacterEntry>();
        final var emitted = new HashSet<String>();

        // The saved roster first, in cycle order: a connected one carries whatever the player pinned, a
        // disconnected one is kept greyed-out only if they pinned something to it. A bare name they never
        // configured earns no row of its own — it reappears when its window does.
        for (final var character : config.characters()) {
            if (liveByName.containsKey(character.name())) {
                entries.add(new CharacterEntry(character, true));
                emitted.add(character.name());
            } else if (character.isPinned()) {
                entries.add(new CharacterEntry(character, false));
                emitted.add(character.name());
            }
        }

        // Then the windows the roster does not know: a character detected but never configured, in the
        // order the cycler ranks them, connected by definition and with nothing pinned to them yet.
        for (final var name : liveByName.keySet()) {
            if (!emitted.contains(name)) {
                entries.add(new CharacterEntry(new Character(name), true));
            }
        }

        // And last, a window at the login screen: the game, but with no character to give it a name.
        if (loginOnScreen) {
            entries.add(new CharacterEntry(new Character(OverlayContent.LOGGING_IN), true));
        }

        return new OverlayContent(entries, hotkeys, config.overlayScale(),
                config.autoPassTurn(), config.autoAcceptTrade(),
                config.econome(), config.spellSetup(),
                activity.recent(), whispers.recent());
    }

    /** Shows the change the player just made, without moving the panel off its character. */
    private void redraw() {
        final var placed = bounds.get();
        if (!view.isVisible() || placed == null) {
            return;
        }
        draw(content(), placed);
    }

    /**
     * Hands the panel what to show, and remembers it — the one way to {@link OverlayView#show}, so the
     * roster poll always compares against what is actually on the screen.
     */
    private void draw(OverlayContent content, Rect where) {
        shown.set(content);
        view.show(content, where);
    }
}
