package fr.minobot.core.domain;

import fr.minobot.i18n.Text;

import java.time.Instant;

/**
 * One thing Minobot did, as the player would tell it: when, what, and about whom.
 *
 * <p>{@code what} is the act — <em>Passed Bravo's turn</em> — and {@code detail} is the circumstance that
 * makes it make sense — <em>from Alpha</em>, <em>4 characters</em>. Split in two because the panel draws
 * them in two columns: the acts line up down the middle of the card and are read as a list, while the
 * details sit at the right and are read only when one of the acts is a surprise.
 *
 * <p>Both are {@link Text}, not yet words: an entry outlives the language it was written in, and the
 * panel phrases it in the one the player reads when it is drawn. A feature says what it did, in terms of
 * characters; the panel puts it into words.
 */
public record Activity(Instant at, Text what, Text detail) {

    /** An act that needs no circumstance: the detail column is simply empty for it. */
    public Activity(Instant at, Text what) {
        this(at, what, Text.NONE);
    }
}
