package fr.minobot.core.domain;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Something another character asks of a character, waiting on their screen for a yes or a no — a group
 * invitation, an exchange, a duel.
 *
 * @param asker the character who asked, by name as the game writes it
 */
public record Request(Kind kind, String asker) {

    public enum Kind {
        GROUP_INVITE,
        TRADE,
        DUEL
    }

    /**
     * The game's wording, the asker first — the same in the box on screen and in the toast:
     * {@code Bravo t'invite à rejoindre son groupe.}, {@code Bravo te propose de faire un échange.},
     * {@code Bravo te défie.} Loose
     * where the OCR is: the apostrophe comes back in either of its forms or as a space, and the accent
     * may be lost.
     */
    private static final Map<Kind, Pattern> WORDINGS = Map.of(
            Kind.GROUP_INVITE, Pattern.compile("^(\\S+)\\s+t\\W{0,2}invite\\s.*groupe", Pattern.CASE_INSENSITIVE),
            Kind.TRADE, Pattern.compile("^(\\S+)\\s+te\\s+propose\\s.*[ée]change", Pattern.CASE_INSENSITIVE),
            Kind.DUEL, Pattern.compile("^(\\S+)\\s+te\\s+d[ée]fie", Pattern.CASE_INSENSITIVE));

    /** The request a game text asks — empty when it asks nothing this application answers. */
    public static Optional<Request> in(String text) {
        final var question = text.strip().replaceAll("\\s+", " ");
        for (final var wording : WORDINGS.entrySet()) {
            final var matcher = wording.getValue().matcher(question);
            if (matcher.find()) {
                return Optional.of(new Request(wording.getKey(), matcher.group(1)));
            }
        }
        return Optional.empty();
    }
}
