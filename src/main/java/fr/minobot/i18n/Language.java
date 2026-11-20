package fr.minobot.i18n;

import java.util.Locale;

/**
 * The languages the player can read Minobot in. French first: it is the game's own language, and the one
 * every session starts in — the choice is the player's for the session, and a restart forgets it.
 *
 * <p>The selector shows the name, {@code FR} and {@code EN}, and lists each language under its
 * {@link #nativeName()}: neither depends on the language being read, so a player who switched to the wrong
 * one can still find the way back.
 */
public enum Language {

    FR("Français"),
    EN("English");

    private final String nativeName;

    Language(String nativeName) {
        this.nativeName = nativeName;
    }

    /** The language's name in itself — {@code Français}, never {@code French}. */
    public String nativeName() {
        return nativeName;
    }

    /** The name of its file of phrases — {@code fr} for {@code /i18n/fr.properties}. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Its flag on the classpath — {@code /flags/fr.png}. */
    public String flagResource() {
        return "/flags/" + code() + ".png";
    }
}
