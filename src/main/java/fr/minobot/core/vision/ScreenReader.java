package fr.minobot.core.vision;

import fr.minobot.core.FightLog;
import fr.minobot.core.FightTurns;
import fr.minobot.core.Requests;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.win32.Frame;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One thing read off a character's window at each look of the {@link ScreenWatcher}, and told on. */
@FunctionalInterface
public interface ScreenReader {

    /** @param seenAt when the frame was taken, on the {@link System#nanoTime()} clock */
    void read(GameWindow window, Frame frame, long seenAt);

    /** The fighter named on the turn banner, told to the fight — any window shows it. */
    static ScreenReader turns(TurnBanner banner, FightTurns turns) {
        return (_, frame, seenAt) -> banner.fighter(frame).ifPresent(fighter -> turns.report(fighter, seenAt));
    }

    /**
     * The request in the box on a character's window, told as made of that character — and its absence,
     * so the same request asked again once answered is a new one.
     */
    static ScreenReader requests(RequestDialog dialog, Requests requests) {
        return (window, frame, seenAt) -> {
            if (dialog.noBox(frame)) {
                requests.cleared(window);
            } else {
                dialog.request(frame).ifPresent(request -> requests.report(window, request, seenAt));
            }
        };
    }

    /**
     * The chat's last lines, told to the fight log — during a fight only: out of one, the chat is the
     * players' talk, and reading every line of it is OCR spent on nothing.
     */
    static ScreenReader fightLog(ChatLog chat, FightLog fightLog, BooleanSupplier fighting) {
        return (_, frame, _) -> {
            if (fighting.getAsBoolean()) {
                chat.lines(frame).ifPresent(fightLog::report);
            }
        };
    }

    /**
     * The end-of-fight screen up in a character's window — looked for only while it is wanted: out of a
     * fight's end, it is two columns of every window copied out at every look for nothing.
     */
    static ScreenReader resultsScreen(BooleanSupplier wanted, Consumer<GameWindow> seen) {
        return (window, frame, _) -> {
            if (wanted.getAsBoolean() && ResultsScreen.isUp(frame)) {
                seen.accept(window);
            }
        };
    }

    /** Whether the window's spell bar is up to be marked, and where its slots are. */
    static ScreenReader spellBar(SpellBars bars) {
        return (window, frame, seenAt) -> bars.report(window, Hud.of(frame), SpellBar.isUp(frame), seenAt);
    }
}
