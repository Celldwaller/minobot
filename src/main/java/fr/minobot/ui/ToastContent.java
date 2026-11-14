package fr.minobot.ui;

import fr.minobot.i18n.Language;
import fr.minobot.i18n.Words;

import java.util.List;

/**
 * What the whisper stack shows: the private messages a background character has received and not yet
 * read, newest last. The immutable snapshot the view draws, exactly as {@link OverlayContent} is for
 * the panel.
 *
 * <p>Cards, not windows or toasts — each says which of the player's characters was whispered, by whom,
 * and the line they sent. The {@code id} is how the view names a card back when it is clicked or its
 * cross is pressed: the model made it, so the model can find it again.
 *
 * <p>The list is in the order the whispers arrived, oldest first. The view stacks them from a fixed
 * base with the last at the bottom, so the newest sits closest to the eye.
 *
 * <p>The scale is the player's — the same {@code overlay_scale} the panel is drawn at, because the
 * stack covers a band of the same game on the same monitor. It is a multiplier of the natural size of
 * every piece of a card, and the view is the only one that knows what those natural sizes are.
 */
public record ToastContent(double scale, Language language, List<Card> cards) {

    public ToastContent {
        cards = List.copyOf(cards);
    }

    /** The words of the card's own frame, in the player's language — the message itself is the sender's. */
    public Words words() {
        return Words.in(language);
    }

    /** One whisper on the stack: who received it, who sent it, and what they said. */
    public record Card(String id, String receiver, String sender, String message) {
    }
}
