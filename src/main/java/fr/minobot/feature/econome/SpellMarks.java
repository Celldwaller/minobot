package fr.minobot.feature.econome;

import fr.minobot.app.Config;
import fr.minobot.app.Settings;
import fr.minobot.core.ActivityLog;
import fr.minobot.core.FightLog;
import fr.minobot.core.FightTurns;
import fr.minobot.core.vision.SpellBars;
import fr.minobot.core.SpellLayouts;
import fr.minobot.core.SpellUsage;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.Cast;
import fr.minobot.core.domain.Character;
import fr.minobot.core.domain.DofusClass;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.core.vision.Hud;
import fr.minobot.core.vision.SpellBar;
import fr.minobot.i18n.Text;
import fr.minobot.i18n.Words;
import fr.minobot.ui.MarksActions;
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
import java.util.function.Function;
import java.util.function.LongSupplier;

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
 *
 * <p>A character uses their class's layout until the setup's button gives them one of their own — for a
 * character who lays out their spells unlike the others of their class. The slots are where the window's
 * {@link Hud} draws them, as last seen.
 */
public final class SpellMarks implements MarksActions {

    private static final Logger log = LoggerFactory.getLogger(SpellMarks.class);

    /** How often the marks follow the game and the mouse — often enough to catch a click's press. */
    private static final Duration FOLLOW_INTERVAL = Duration.ofMillis(20);

    /** From a click on a slot to the cast it starts: a target to pick, an animation to play. */
    static final Duration LESSON = Duration.ofSeconds(10);

    /** {@code VK_LBUTTON}. */
    private static final int LEFT_BUTTON = 0x01;

    static final String SETUP_HINT = "marks.setup_hint";
    static final String NO_BAR_HINT = "marks.no_bar_hint";
    static final String UNPLACED_HINT = "marks.unplaced_hint";
    static final String OWN_LAYOUT = "marks.own_layout";
    static final String CLASS_LAYOUT = "marks.class_layout";
    static final String WEAPON_LABEL = "marks.weapon";
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

    /** The character whose setup is drawn, whom the setup's button is about; {@code null} for none. */
    private volatile String inSetup;

    public SpellMarks(WindowApi api, WindowManager windows, Settings settings, FightTurns turns,
                      FightLog fightLog, SpellUsage usage, SpellBars bars, SpellLayouts layouts,
                      ActivityLog activity, BooleanSupplier panelUp, Function<MarksActions, MarksView> viewFactory) {
        this(api, windows, settings, turns, fightLog, usage, bars, layouts, activity, panelUp, viewFactory,
                System::nanoTime, task -> Thread.ofVirtual().name("spell-marks-follow").start(task));
    }

    /**
     * @param nanos    a {@link System#nanoTime()}-like clock
     * @param follower starts the follow loop on a thread of its own
     */
    SpellMarks(WindowApi api, WindowManager windows, Settings settings, FightTurns turns,
               FightLog fightLog, SpellUsage usage, SpellBars bars, SpellLayouts layouts,
               ActivityLog activity, BooleanSupplier panelUp, Function<MarksActions, MarksView> viewFactory, LongSupplier nanos,
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
        this.view = viewFactory.apply(this);
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

    /** The setup's button: the character in setup takes a layout of their own, or their class's back. */
    @Override
    public void act() {
        final var character = inSetup;
        if (character != null) {
            Thread.ofVirtual().name("spell-layouts-own").start(() -> layouts.toggleOwn(character, classOf(character)));
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
                .map(cursor -> SpellBar.slotAt(bars.hud(game.window().hwnd()),
                        new Point(cursor.x() - game.area().left(), cursor.y() - game.area().top()),
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
            activity.record(Text.of("activity.challenge_lost"),
                    Text.of("activity.cast_twice", cast.fighter(), cast.action()));
        }
    }

    private MarksContent content(Game game) {
        final var config = settings.get();
        final var words = Words.in(config.language());
        final var name = game.window().name();
        final var layout = layoutOf(name);
        final var width = game.area().width();
        final var height = game.area().height();
        final var barUp = bars.isUp(game.window().hwnd());
        final var hud = bars.hud(game.window().hwnd());

        final var marks = new ArrayList<Mark>();
        var hint = "";
        var action = "";
        inSetup = config.spellSetup() ? name : null;
        if (config.spellSetup()) {
            if (classOf(name) != null) {
                action = words.get(layouts.hasOwn(name) ? CLASS_LAYOUT : OWN_LAYOUT);
            }
            if (barUp) {
                final var hovered = cursorIn(game);
                for (var slot = 0; slot < SpellBar.slots(hud); slot++) {
                    final var spell = slot == SpellBar.WEAPON ? Optional.of(words.get(WEAPON_LABEL)) : layouts.spellAt(layout, slot);
                    final var label = hovered.isPresent() && hovered.getAsInt() == slot ? spell.orElse(UNKNOWN_LABEL) : "";
                    marks.add(new Mark(SpellBar.slot(hud, slot, width, height),
                            spell.isPresent() ? Kind.KNOWN : Kind.UNKNOWN, label));
                }
                hint = words.get(SETUP_HINT, name, layout);
            } else {
                hint = words.get(NO_BAR_HINT);
            }
        }
        if (config.econome() && turns.inFight()) {
            final var unplaced = new ArrayList<String>();
            for (final var cast : usage.of(name)) {
                final var slot = cast.weapon() ? OptionalInt.of(SpellBar.WEAPON) : layouts.slotOf(layout, cast.action());
                if (slot.isEmpty() || slot.getAsInt() >= SpellBar.slots(hud)) {
                    unplaced.add(cast.action());
                } else if (barUp) {
                    marks.add(new Mark(SpellBar.slot(hud, slot.getAsInt(), width, height), Kind.USED, ""));
                }
            }
            if (hint.isEmpty() && !unplaced.isEmpty()) {
                hint = words.get(UNPLACED_HINT, String.join(", ", unplaced));
            }
        }
        return new MarksContent(config.overlayScale(), marks, hint, action);
    }

    private String layoutOf(String character) {
        return layouts.layoutOf(character, classOf(character));
    }

    /** The class the player pinned to the character, {@code null} when they have not. */
    private DofusClass classOf(String character) {
        return settings.get().characters().stream()
                .filter(it -> it.name().equalsIgnoreCase(character))
                .map(Character::clazz)
                .findFirst().orElse(null);
    }

    private record Game(GameWindow window, Rect area) {
    }

    private record Lesson(String character, int slot, long at) {
    }
}
