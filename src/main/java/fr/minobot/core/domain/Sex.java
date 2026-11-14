package fr.minobot.core.domain;

import java.util.Locale;

/**
 * A character's sex — the second half of what picks their icon, next to their {@link DofusClass}.
 *
 * <p>Like the class, it is the player's to set and the game window says nothing of it, so it too is
 * carried in the configuration by character name and persisted with it. It exists because the class
 * icons come in two, one per sex: the {@link #suffix()} is what tells {@code iop_m.svg} from
 * {@code iop_f.svg}.
 */
public enum Sex {

    MALE("m"),
    FEMALE("f");

    private final String suffix;

    Sex(String suffix) {
        this.suffix = suffix;
    }

    /** What the sex adds to an icon's file name — {@code "m"} in {@code iop_m.svg}. */
    public String suffix() {
        return suffix;
    }

    /**
     * Where the picker's toggle finds this sex's name in the player's language — {@code sex.male}.
     *
     * <p>The word, not the initial: {@code M}/{@code F} needs a legend the picker has no room for, and
     * the toggle is two segments wide either way.
     */
    public String key() {
        return "sex." + name().toLowerCase(Locale.ROOT);
    }
}
