package fr.minobot.core.vision;

import fr.minobot.core.domain.Request;
import fr.minobot.win32.Capture;
import fr.minobot.win32.TextReader;

import java.util.Optional;

/**
 * Reads, off a capture of a character's window, the request waiting there for a yes or a no.
 *
 * <p>The game asks in a box in the middle of the stage — a dark title bar ({@code Groupe},
 * {@code Echange}, {@code Défi}), a beige body with the question ({@code Bravo t'invite à rejoindre son groupe.
 * Acceptes-tu ?}), and {@code Oui} / {@code Non} below. It is drawn in the asked character's window only,
 * and stays there until it is answered. The box is checked by its colours before the question is read;
 * the question is then matched by its wording ({@link Request#in}), which names the asker.
 *
 * <p>Positions are the stage's (see {@link GameStage}), measured on a 2560x1369 window.
 */
public final class RequestDialog {

    private static final int TITLE_BAR = 0x514A3C;
    private static final int BODY_BEIGE = 0xD5CFAA;

    /** On the title bar, right of where its title is written. */
    private static final double[][] TITLE_POINTS = {{300, 150}, {520, 150}};

    /** The body's four corners, inside its white rim. */
    private static final double[][] BODY_POINTS = {{205, 172}, {537, 172}, {205, 283}, {537, 283}};

    /** The question: the body's width, from under the title bar to above the buttons. */
    private static final double QUESTION_LEFT = 205;
    private static final double QUESTION_RIGHT = 537;
    private static final double QUESTION_TOP = 170;
    private static final double QUESTION_BOTTOM = 220;

    /** The OCR finds nothing in text that touches its image's edge. */
    private static final int MARGIN = 20;

    private final TextReader reader;

    public RequestDialog(TextReader reader) {
        this.reader = reader;
    }

    /** The request on screen — empty when there is no box up, or it asks something else. */
    public Optional<Request> request(Capture capture) {
        final var stage = GameStage.of(capture);
        if (!isUp(capture, stage)) {
            return Optional.empty();
        }
        final var question = capture.crop(stage.x(QUESTION_LEFT), stage.y(QUESTION_TOP),
                stage.x(QUESTION_RIGHT), stage.y(QUESTION_BOTTOM));
        return reader.read(question.padded(MARGIN, BODY_BEIGE)).flatMap(Request::in);
    }

    private static boolean isUp(Capture capture, GameStage stage) {
        return all(capture, stage, TITLE_POINTS, TITLE_BAR) && all(capture, stage, BODY_POINTS, BODY_BEIGE);
    }

    private static boolean all(Capture capture, GameStage stage, double[][] points, int rgb) {
        for (final var point : points) {
            final var x = stage.x(point[0]);
            final var y = stage.y(point[1]);
            if (x < 0 || y < 0 || x >= capture.width() || y >= capture.height() || capture.rgb(x, y) != rgb) {
                return false;
            }
        }
        return true;
    }
}
