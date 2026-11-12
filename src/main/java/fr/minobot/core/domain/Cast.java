package fr.minobot.core.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A spell cast or a weapon swung, as the fight log tells it: {@code Foxtrot lance Flèche d'Expiation.},
 * {@code Foxtrot attaque avec Sceptre du Minotot.}.
 *
 * <p>A critical failure is no cast: the log writes {@code (Échec critique)} on the line after, and the
 * spell may be cast again. That line is the fight log's to read ({@code FightLog}), not this one's.
 *
 * @param fighter who cast, as the log writes them — a character's name, or a monster's
 * @param action  the spell, or the weapon
 * @param weapon  whether it was the weapon
 */
public record Cast(String fighter, String action, boolean weapon) {

    private static final Pattern SPELL = Pattern.compile("^(\\S.*?) lance (\\S.*?)\\.?$");
    private static final Pattern WEAPON = Pattern.compile("^(\\S.*?) attaque avec (\\S.*?)\\.?$");

    /** The cast a line of the fight log tells — empty when it tells something else. */
    public static Optional<Cast> in(String line) {
        final var weapon = WEAPON.matcher(line.strip());
        if (weapon.matches()) {
            return Optional.of(new Cast(weapon.group(1), weapon.group(2), true));
        }
        final var spell = SPELL.matcher(line.strip());
        if (spell.matches()) {
            return Optional.of(new Cast(spell.group(1), spell.group(2), false));
        }
        return Optional.empty();
    }

    /**
     * What an action is known by, whatever the OCR made of its accents and case: the same line read at
     * another height of the chat may come back with an accent fewer.
     */
    public static String key(String action) {
        final var bare = Normalizer.normalize(action, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return bare.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    /** Whether this is the named fighter's: names are compared as the game compares them, case aside. */
    public boolean by(String name) {
        return fighter.equalsIgnoreCase(name);
    }
}
