package fr.minobot.core.vision;

import fr.minobot.core.domain.Request;
import fr.minobot.win32.Frame;
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

    /** On the title bar, right of where its title is written: one row, from one point to the other. */
    private static final double TITLE_LEFT = 300;
    private static final double TITLE_RIGHT = 520;
    private static final double TITLE_ROW = 150;
    private static final double[][] TITLE_POINTS = {{TITLE_LEFT, TITLE_ROW}, {TITLE_RIGHT, TITLE_ROW}};

    /** The body's four corners, inside its white rim. */
    private static final double[][] BODY_POINTS = {{205, 172}, {537, 172}, {205, 283}, {537, 283}};

    /** The question: the body's width, from under the title bar to above the buttons. */
    private static final double QUESTION_LEFT = 205;
    private static final double QUESTION_RIGHT = 537;
    private static final double QUESTION_TOP = 170;
    private static final double QUESTION_BOTTOM = 220;

    /** The body as read once the title bar is seen: its corners and the question lie within, edges included. */
    private static final double BODY_LEFT = 205;
    private static final double BODY_TOP = 170;
    private static final double BODY_RIGHT = 537;
    private static final double BODY_BOTTOM = 283;

    /** The OCR finds nothing in text that touches its image's edge. */
    private static final int MARGIN = 20;

    private final TextReader reader;

    public RequestDialog(TextReader reader) {
        this.reader = reader;
    }

    /**
     * The request on screen — empty when there is no box up, or it asks something else.
     *
     * <p>Almost every look finds no box: one row of its title bar says so, and the body — a megabyte of a
     * 2560 px window, copied at ten looks a second — is only read when the row is there.
     */
    public Optional<Request> request(Frame frame) {
        final var stage = GameStage.of(frame);
        return titleRow(frame, stage)
                .filter(title -> all(title, stage, TITLE_POINTS, TITLE_BAR))
                .flatMap(_ -> Region.of(frame,
                        stage.x(BODY_LEFT), stage.y(BODY_TOP), stage.x(BODY_RIGHT) + 1, stage.y(BODY_BOTTOM) + 1))
                .filter(body -> all(body, stage, BODY_POINTS, BODY_BEIGE))
                .map(body -> body.crop(stage.x(QUESTION_LEFT), stage.y(QUESTION_TOP),
                        stage.x(QUESTION_RIGHT), stage.y(QUESTION_BOTTOM)))
                .flatMap(question -> reader.read(question.padded(MARGIN, BODY_BEIGE)))
                .flatMap(Request::in);
    }

    /**
     * Whether the frame surely shows no box: its title row was read, and no title bar is on it. A frame
     * that cannot be read, or a box whose question cannot, says nothing — taken for "no box", a box still
     * up would be asked again at its next sighting.
     */
    public boolean noBox(Frame frame) {
        final var stage = GameStage.of(frame);
        return titleRow(frame, stage).filter(title -> !all(title, stage, TITLE_POINTS, TITLE_BAR)).isPresent();
    }

    private static Optional<Region> titleRow(Frame frame, GameStage stage) {
        return Region.of(frame, stage.x(TITLE_LEFT), stage.y(TITLE_ROW), stage.x(TITLE_RIGHT) + 1, stage.y(TITLE_ROW) + 1);
    }

    private static boolean all(Region region, GameStage stage, double[][] points, int rgb) {
        for (final var point : points) {
            if (!region.is(stage.x(point[0]), stage.y(point[1]), rgb)) {
                return false;
            }
        }
        return true;
    }
}
