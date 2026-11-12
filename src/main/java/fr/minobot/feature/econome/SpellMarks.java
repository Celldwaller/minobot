package fr.minobot.feature.econome;

import fr.minobot.app.Config;
import fr.minobot.app.Settings;
import fr.minobot.core.ActivityLog;
import fr.minobot.core.FightLog;
import fr.minobot.core.FightTurns;
import fr.minobot.core.SpellBars;
import fr.minobot.core.SpellLayouts;
import fr.minobot.core.SpellUsage;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.Cast;
import fr.minobot.core.domain.Character;
import fr.minobot.core.domain.DofusClass;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.core.vision.SpellBar;
import fr.minobot.ui.MarksContent;
import fr.minobot.ui.MarksContent.Kind;
import fr.minobot.ui.MarksContent.Mark;
import fr.minobot.ui.MarksView;
import fr.minobot.win32.Point;
import fr.minobot.win32.Rect;
import fr.minobot.win32.WindowApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Crosses out, on the spell bar of the character the player is on, every spell that character cast this
 * fight — the help for the <em>Économe</em> challenge — and, while the player sets the bar up, frames its
 * slots and learns which spell each one holds.
 *
 * <p>A slot is learned by playing: the player clicks it, and the fight log then says what that character
 * cast ({@link #LESSON}). Out of the setup, only a spell not known yet is learned so — a click on a slot
 * followed by a spell cast from a key would otherwise move a known spell to the wrong slot.
 *
 * <p>Like the auto-pass banner, a virtual thread follows the foreground game while either switch is on:
 * Windows says nothing when a window moves, nor when a mouse button goes down in another process's window,
 * so both are polled. The marks are taken down while there is nothing to mark — no fight, or no bar on
 * screen: a fighter's sheet over it, its items tab — and while the panel covers the game.
 */
public final class SpellMarks {

    private static final Logger log = LoggerFactory.getLogger(SpellMarks.class);

    /** How often the marks follow the game and the mouse — often enough to catch a click's press. */
    private static final Duration FOLLOW_INTERVAL = Duration.ofMillis(20);

    /** From a click on a slot to the cast it starts: a target to pick, an animation to play. */
    static final Duration LESSON = Duration.ofSeconds(10);

    /** {@code VK_LBUTTON}. */
    private static final int LEFT_BUTTON = 0x01;

    static final String SETUP_HINT = "Spell setup · %s, layout %s — click a spell, then cast it";
    static final String NO_BAR_HINT = "Spell setup · show the spell bar";
    static final String UNPLACED_HINT = "Cast, not on the bar yet: %s";
    static final String WEAPON_LABEL = "Weapon";
    static final String UNKNOWN_LABEL = "?";

    private final WindowApi api;
    private final WindowManager windows;
    private final Settings settings;
    private final FightTurns turns;
    private final SpellUsage usage;
    private final SpellBars bars;
    private final SpellLayouts layouts;
    private final ActivityLog activity;
    private final BooleanSupplier panelUp;
    private final MarksView view;
    private final LongSupplier nanos;
    private final Consumer<Runnable> follower;

    private final AtomicBoolean following = new AtomicBoolean();

    /** The follower's: whether the button was down at its last tick, and what it last drew where. */
    private boolean buttonWasDown;
    private MarksContent drawn;
    private Rect drawnOn;

    /** The last slot clicked, waiting for its cast; {@code null} for none. */
    private volatile Lesson lesson;

    public SpellMarks(WindowApi api, WindowManager windows, Settings settings, FightTurns turns,
                      FightLog fightLog, SpellUsage usage, SpellBars bars, SpellLayouts layouts,
                      ActivityLog activity, BooleanSupplier panelUp, Supplier<MarksView> viewFactory) {
        this(api, windows, settings, turns, fightLog, usage, bars, layouts, activity, panelUp, viewFactory,
                System::nanoTime, task -> Thread.ofVirtual().name("spell-marks-follow").start(task));
    }

    /**
     * @param nanos    a {@link System#nanoTime()}-like clock
     * @param follower starts the follow loop on a thread of its own
     */
    SpellMarks(WindowApi api, WindowManager windows, Settings settings, FightTurns turns,
               FightLog fightLog, SpellUsage usage, SpellBars bars, SpellLayouts layouts,
               ActivityLog activity, BooleanSupplier panelUp, Supplier<MarksView> viewFactory, LongSupplier nanos,
               Consumer<Runnable> follower) {
        this.api = api;
        this.windows = windows;
        this.settings = settings;
        this.turns = turns;
        this.usage = usage;
        this.bars = bars;
        this.layouts = layouts;
        this.activity = activity;
        this.panelUp = panelUp;
        this.view = viewFactory.get();
        this.nanos = nanos;
        this.follower = follower;

        fightLog.onCast(this::heard);
        usage.onRepeat(this::repeated);
        settings.onChange(this::onChange);
        onChange(settings.get());
    }

    private static boolean wanted(Config config) {
        return config.econome() || config.spellSetup();
    }

    void onChange(Config config) {
        if (wanted(config) && following.compareAndSet(false, true)) {
            follower.accept(this::follow);
        }
    }

    private void follow() {
        try {
            while (wanted(settings.get())) {
                tick();
                Thread.sleep(FOLLOW_INTERVAL);
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("Unexpected error while marking the spells.", e);
        } finally {
            following.set(false);
            if (view.isVisible()) {
                view.hide();
            }
            // A switch turned on again between the loop's guard and here must not be left without marks.
            if (wanted(settings.get()) && !Thread.currentThread().isInterrupted()) {
                onChange(settings.get());
            }
        }
    }

    /** One step of the follower: the click, if the button just went down, and what to draw. */
    void tick() {
        final var game = windows.foregroundGameWindow()
                .filter(window -> !api.isIconic(window.hwnd()))
                .flatMap(window -> api.clientArea(window.hwnd()).map(area -> new Game(window, area)));

        final var down = api.isKeyDown(LEFT_BUTTON);
        if (down && !buttonWasDown) {
            game.ifPresent(this::clicked);
        }
        buttonWasDown = down;

        final var content = game.filter(_ -> !panelUp.getAsBoolean()).map(this::content)
                .filter(marks -> !marks.isEmpty());
        if (content.isPresent()) {
            if (!view.isVisible() || !content.get().equals(drawn) || !game.get().area().equals(drawnOn)) {
                view.show(content.get(), game.get().area());
                drawn = content.get();
                drawnOn = game.get().area();
            }
        } else if (view.isVisible()) {
            view.hide();
        }
    }

    private void clicked(Game game) {
        cursorIn(game).ifPresent(slot -> {
            if (slot != SpellBar.WEAPON) {
                lesson = new Lesson(game.window().name(), slot, nanos.getAsLong());
            }
        });
    }

    private OptionalInt cursorIn(Game game) {
        return api.cursorPosition()
                .map(cursor -> SpellBar.slotAt(new Point(cursor.x() - game.area().left(), cursor.y() - game.area().top()),
                        game.area().width(), game.area().height()))
                .orElse(OptionalInt.empty());
    }

    /** On the screen watcher's thread: the lesson is learned on a thread of its own, which writes a file. */
    private void heard(Cast cast) {
        final var waiting = lesson;
        if (cast.weapon() || waiting == null || !cast.by(waiting.character())
                || nanos.getAsLong() - waiting.at() > LESSON.toNanos()) {
            return;
        }
        lesson = null;
        final var layout = layoutOf(waiting.character());
        if (!settings.get().spellSetup() && layouts.slotOf(layout, cast.action()).isPresent()) {
            return;
        }
        Thread.ofVirtual().name("spell-layouts-learn").start(() -> layouts.learn(layout, waiting.slot(), cast.action()));
    }

    private void repeated(Cast cast) {
        if (settings.get().econome() && windows.windowOf(cast.fighter()).isPresent()) {
            activity.record("Économe broken", cast.fighter() + " cast " + cast.action() + " twice");
        }
    }

    private MarksContent content(Game game) {
        final var config = settings.get();
        final var name = game.window().name();
        final var layout = layoutOf(name);
        final var width = game.area().width();
        final var height = game.area().height();
        final var barUp = bars.isUp(game.window().hwnd());

        final var marks = new ArrayList<Mark>();
        var hint = "";
        if (config.spellSetup()) {
            if (barUp) {
                final var hovered = cursorIn(game);
                for (var slot = 0; slot < SpellBar.SLOTS; slot++) {
                    final var spell = slot == SpellBar.WEAPON ? Optional.of(WEAPON_LABEL) : layouts.spellAt(layout, slot);
                    final var label = hovered.isPresent() && hovered.getAsInt() == slot ? spell.orElse(UNKNOWN_LABEL) : "";
                    marks.add(new Mark(SpellBar.slot(slot, width, height),
                            spell.isPresent() ? Kind.KNOWN : Kind.UNKNOWN, label));
                }
                hint = SETUP_HINT.formatted(name, layout);
            } else {
                hint = NO_BAR_HINT;
            }
        }
        if (config.econome() && turns.inFight()) {
            final var unplaced = new ArrayList<String>();
            for (final var cast : usage.of(name)) {
                final var slot = cast.weapon() ? OptionalInt.of(SpellBar.WEAPON) : layouts.slotOf(layout, cast.action());
                if (slot.isEmpty()) {
                    unplaced.add(cast.action());
                } else if (barUp) {
                    marks.add(new Mark(SpellBar.slot(slot.getAsInt(), width, height), Kind.USED, ""));
                }
            }
            if (hint.isEmpty() && !unplaced.isEmpty()) {
                hint = UNPLACED_HINT.formatted(String.join(", ", unplaced));
            }
        }
        return new MarksContent(config.overlayScale(), marks, hint);
    }

    private String layoutOf(String character) {
        final DofusClass clazz = settings.get().characters().stream()
                .filter(it -> it.name().equalsIgnoreCase(character))
                .map(Character::clazz)
                .findFirst().orElse(null);
        return layouts.layoutOf(character, clazz);
    }

    private record Game(GameWindow window, Rect area) {
    }

    private record Lesson(String character, int slot, long at) {
    }
}
