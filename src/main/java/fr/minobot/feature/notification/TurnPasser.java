package fr.minobot.feature.notification;

import fr.minobot.app.Settings;
import fr.minobot.core.ActivityLog;
import fr.minobot.core.FightTurns;
import fr.minobot.core.FocusManager;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.core.input.Input;
import fr.minobot.i18n.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.KeyEvent;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Ends the turn of a character the game says it is the turn of — the feature the overlay's
 * <em>Auto-pass turns</em> switch drives.
 *
 * <p>It hears of a turn from {@link FightTurns}: the banner that opens the turn on the screen, which
 * still speaks for a window gone deaf to toasts. When the switch is
 * on, it turns that turn into a keypress — it brings the character up and presses the game's end-turn
 * key — so a table of alts plays itself while the player watches.
 *
 * <p>It passes <em>every</em> character's turn, the one in the foreground included. The switch is the
 * player saying they have stepped away from the table, not that they are playing one of the seats:
 * there is no turn to leave to them. That is why there is no "except the character I am on" here, and
 * why it is off by default.
 *
 * <p>Like the group invitation, it focuses a window and then types into it, so it holds the foreground
 * for the length of that ({@link FocusManager#takeOver()}). Without it the turn auto-focus, which
 * hears of the very same turn, would land between the focus and the keypress and press the
 * end-turn key into another character's window. It is a combat feature, and the group invitation is
 * done before the fight: the two are not meant to run at once.
 */
public final class TurnPasser {

    private static final Logger log = LoggerFactory.getLogger(TurnPasser.class);

    /**
     * The game's end-turn key. Dictated by the game and not the player, so it is a constant here rather
     * than a setting — {@code F1} is what ends a turn in Dofus Retro.
     */
    private static final int END_TURN_KEY = KeyEvent.VK_F1;

    private final WindowManager windows;
    private final Input input;
    private final FocusManager focus;
    private final Settings settings;
    private final ActivityLog activity;

    /** One turn at a time: a focus-then-press must finish before the next turn's begins, or the key
     *  meant for one window is pressed into another. */
    private final ReentrantLock passing = new ReentrantLock();

    public TurnPasser(WindowManager windows, Input input, FocusManager focus,
                      FightTurns turns, Settings settings, ActivityLog activity) {
        this.windows = windows;
        this.input = input;
        this.focus = focus;
        this.settings = settings;
        this.activity = activity;

        turns.onTurn(this::onTurn);
    }

    /** Runs on a virtual thread, one per turn, from {@link FightTurns}. */
    void onTurn(String fighter) {
        // Read at every turn, never held: the overlay flips this switch while a fight is under way.
        if (!settings.get().autoPassTurn()) {
            return;
        }
        // A monster's turn, or another player's character's: nobody with a window here.
        windows.windowOf(fighter).ifPresent(this::passTurn);
    }

    /**
     * Brings the character up and ends its turn.
     *
     * <p>The focus and the keypress are one act: nothing may fall between them — not the auto-focus
     * hearing of the same turn, not the next character's turn — or the key ends a turn in the wrong
     * window. {@link FocusManager#takeOver()} keeps the auto-focus out; {@link #passing} keeps the next
     * turn out.
     */
    private void passTurn(GameWindow character) {
        passing.lock();
        try (final var _ = focus.takeOver()) {
            if (focus.focus(character.hwnd())) {
                input.pressKey(END_TURN_KEY);
                log.info("Passed '{}'s turn.", character.name());
                activity.record(Text.of("activity.turn_passed", character.name()), Text.of("activity.auto_pass"));
            } else {
                log.warn("Could not focus '{}': its turn was not passed.", character.name());
            }
        } finally {
            passing.unlock();
        }
    }
}
