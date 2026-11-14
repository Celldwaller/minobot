package fr.minobot.ui;

import fr.minobot.app.Feature;
import fr.minobot.core.domain.DofusClass;
import fr.minobot.core.domain.Sex;
import fr.minobot.i18n.Language;

import java.util.List;
import java.util.Optional;

/**
 * What the player can ask for through the panel. Implemented by the controller, so the view itself
 * never touches the configuration, the keyboard or the game.
 *
 * <p>Every one of these runs on whichever thread the view calls it from — Swing's, in practice. None
 * of them blocks for long except {@link #captureHotkey()}, which by nature waits for a human.
 */
public interface OverlayActions {

    /**
     * Reads the panel, and everything else Minobot says, in another language — the header's selector.
     * Session-only, like every switch: the next launch opens in French again.
     */
    void changeLanguage(Language language);

    /** The characters as the player has just dragged them: the order the cycler will follow. */
    void reorder(List<String> characters);

    /**
     * Binds the feature to a combination. A blank one turns the feature off — that is the toggle, and
     * the reason there is no {@code *_enabled} flag anywhere.
     */
    void rebind(Feature feature, String combination);

    /**
     * Pins a class to a character — how the class picker lands. Keyed by name, and persisted like the
     * cycle order, so the choice survives a restart. Picking again simply overwrites the old class.
     */
    void assignClass(String character, DofusClass clazz);

    /**
     * Pins a sex to a character — the picker's other half, kept and persisted like the class. Unlike a
     * class pick it leaves the picker open, so the player sets a sex and then chooses a class in it.
     */
    void assignSex(String character, Sex sex);

    /**
     * Drops a character from the saved roster — its class and its sex with it — so a character the player
     * no longer runs stops taking up a row.
     *
     * <p>Offered on the panel only for a <em>disconnected, pinned</em> character: a connected one is on
     * screen and would come straight back, and an unpinned one was never saved to begin with. The reverse
     * of {@link #assignClass}/{@link #assignSex}, and persisted the same way.
     */
    void forget(String character);

    /**
     * Draws the panel bigger or smaller — what the player has just dragged the slider to.
     *
     * <p>A value outside what the slider offers is brought back inside it: the configuration is the one
     * holding the bounds, not the view.
     */
    void rescale(double scale);

    /**
     * Switches the automatic turn-passer on or off — a feature with a state rather than a key.
     * Session-only, like every edit made through the panel.
     */
    void toggleAutoPassTurn(boolean on);

    /**
     * Switches the automatic trade-accepter on or off — the panel's other stateful toggle.
     * Session-only, like every edit made through the panel.
     */
    void toggleAutoAcceptTrade(boolean on);

    /** Switches the crosses on the spells cast this fight on or off — the <em>Économe</em> help. */
    void toggleEconome(boolean on);

    /** Switches the spell bar's setup on or off: its slots framed, so the player can teach them. */
    void toggleSpellSetup(boolean on);

    /**
     * Forgets the spells cast this fight, and their crosses with them — what a new fight does, for the
     * one that began too soon after the last to be told apart from it.
     */
    void resetEconome();

    /**
     * Brings up the character a whisper was sent to, so the player can answer it — the panel's version of
     * the click the toast offered before it faded.
     *
     * <p>It also takes the panel down: the player asked to <em>go there</em>, and a panel left covering
     * the window they were sent to would be in the way of the very answer they went to write.
     */
    void openWhisper(String whisper);

    /** Empties the whisper list. They are read and then in the way; nothing on disk remembers them. */
    void clearWhispers();

    /**
     * Waits for the player to press the key they want, and reads it back — {@code "shift+F7"}.
     *
     * <p>Empty if they pressed nothing in time. No hotkey fires while this is waiting: a key being
     * named is not a key being used.
     */
    Optional<String> captureHotkey();
}
