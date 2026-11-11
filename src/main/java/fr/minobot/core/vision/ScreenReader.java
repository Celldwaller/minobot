package fr.minobot.core.vision;

import fr.minobot.core.FightTurns;
import fr.minobot.core.Requests;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.win32.Capture;

/** One thing read off a character's window at each look of the {@link ScreenWatcher}, and told on. */
@FunctionalInterface
public interface ScreenReader {

    /** @param seenAt when the capture was taken, on the {@link System#nanoTime()} clock */
    void read(GameWindow window, Capture capture, long seenAt);

    /** The fighter named on the turn banner, told to the fight — any window shows it. */
    static ScreenReader turns(TurnBanner banner, FightTurns turns) {
        return (_, capture, seenAt) -> banner.fighter(capture).ifPresent(fighter -> turns.report(fighter, seenAt));
    }

    /** The request in the box on a character's window, told as made of that character. */
    static ScreenReader requests(RequestDialog dialog, Requests requests) {
        return (window, capture, seenAt) ->
                dialog.request(capture).ifPresent(request -> requests.report(window, request, seenAt));
    }
}
