package fr.minobot.i18n;

import java.util.List;
import java.util.Objects;

/**
 * Something to tell the player, not yet put into words: a key into the phrases and what fills its
 * placeholders. {@link Words} phrases it in whichever language the player reads at the moment it is drawn.
 *
 * <p>It exists for what outlives the moment it was said — an activity line written in French is still on
 * the panel when the player switches to English, and must be read in English then. A string phrased at
 * the time would be stuck in the language it was written in.
 *
 * <p>{@link Verbatim} is for words that are no one's to translate: what the game itself wrote, a toast's
 * message — already in the game's language, and passed on as they came.
 */
public sealed interface Text {

    /** Nothing to say — an activity's empty detail column. */
    Text NONE = new Verbatim("");

    /** @param args filling {@code {0}}, {@code {1}}… — a {@code Text} among them is phrased too */
    static Text of(String key, Object... args) {
        return new Phrase(key, List.of(args));
    }

    static Text verbatim(String text) {
        return new Verbatim(Objects.requireNonNullElse(text, ""));
    }

    /** Whether there is nothing to draw — never true of a phrase, which always says something. */
    boolean isBlank();

    record Phrase(String key, List<Object> args) implements Text {

        public Phrase {
            args = List.copyOf(args);
        }

        @Override
        public boolean isBlank() {
            return false;
        }
    }

    record Verbatim(String text) implements Text {

        @Override
        public boolean isBlank() {
            return text.isBlank();
        }
    }
}
