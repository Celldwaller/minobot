package fr.minobot.ui;

import fr.minobot.app.Feature;
import fr.minobot.core.domain.Activity;
import fr.minobot.core.domain.Whisper;

import java.util.List;
import java.util.Map;

/**
 * What the panel shows: the characters Minobot has found, in the order the player cycles through them,
 * the key each feature currently answers to, and — the panel's other half — what Minobot has just been
 * doing while the player was busy.
 *
 * <p>Characters, not windows — the panel is the story the player tells, and they cycle through
 * <em>Bravo</em> and <em>Charlie</em>, not through {@code 0x000407A2}. Each {@link CharacterEntry} carries
 * the character — its name and whatever the player pinned to it, a class, a sex — beside whether their
 * window is open right now, so the panel reads one list rather than a list of names beside a map of
 * classes beside a map of sexes beside a set of who is connected.
 *
 * <p>A blank hotkey is a feature the player has turned off; there is no separate flag for it. The
 * order of {@code hotkeys} means nothing: walk {@link Feature#values()} to lay them out.
 *
 * <p>The scale is how big the player wants the panel drawn — the panel covers the game, and the game
 * is played on screens of every size. It is a multiplier of the natural size of every piece of the
 * panel, and the view is the only one that knows what those natural sizes are.
 *
 * <p>{@code autoPassTurn}, {@code autoAcceptTrade}, {@code econome} and {@code spellSetup} are the features
 * the panel shows a state for rather than a key: they have no hotkey, so the panel draws each as an explicit
 * on/off switch.
 *
 * <p>{@code activity} and {@code whispers} both arrive <strong>newest first</strong>, which is the order
 * the panel reads them in: the player opens it to see what just happened, so the last thing to have
 * happened is the first thing drawn. They are the record of everything the features did off-screen —
 * without them the panel would show what Minobot <em>is set to</em> and never what it <em>did</em>.
 *
 * <p>The list holds the characters on screen <em>and</em> the ones the player has pinned a class or sex
 * to but is not playing right now — the latter drawn greyed-out, {@code connected == false}, so a
 * configured character does not vanish the moment its window closes. An unpinned character is a bare name
 * and appears only while its window is open. A character with no class has none pinned yet, and the panel
 * offers to give it one; one with no sex is drawn as male.
 */
public record OverlayContent(List<CharacterEntry> characters, Map<Feature, String> hotkeys, double scale,
                             boolean autoPassTurn, boolean autoAcceptTrade,
                             boolean econome, boolean spellSetup,
                             List<Activity> activity, List<Whisper> whispers) {

    /**
     * What the panel lists a game window with no character loaded yet under — a login or selection
     * screen. It has a window but no name to be cycled by or given a class to, so it rides in the list
     * as a {@link Character} under this name and is left out of both the saved order and the classes.
     * Here rather than in the controller so the view can recognise the row without hardcoding the string.
     */
    public static final String LOGGING_IN = "(logging in…)";

    public OverlayContent {
        characters = List.copyOf(characters);
        hotkeys = Map.copyOf(hotkeys);
        activity = List.copyOf(activity);
        whispers = List.copyOf(whispers);
    }

    /** How many of the listed characters have their window open — what the team heading counts. */
    public long online() {
        return characters.stream().filter(CharacterEntry::connected).count();
    }
}
