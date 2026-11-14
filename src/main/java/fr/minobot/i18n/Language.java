package fr.minobot.i18n;

import java.util.Locale;

/**
 * The languages the player can read Minobot in. French first: it is the game's own language, and the one
 * every session starts in — the choice is the player's for the session, and a restart forgets it.
 *
 * <p>The name is also what the selector shows, {@code FR} and {@code EN}: a language is picked by its
 * code, written the same in every language, so a player who switched to the wrong one can still find
 * the way back.
 */
public enum Language {

    FR,
    EN;

    /** The name of its file of phrases — {@code fr} for {@code /i18n/fr.properties}. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
