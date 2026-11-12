package fr.minobot.core.vision;

import fr.minobot.core.domain.Request;
import fr.minobot.win32.FakeTextReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static fr.minobot.core.vision.BannerCaptures.BEIGE;
import static fr.minobot.core.vision.BannerCaptures.INK;
import static fr.minobot.core.vision.BannerCaptures.banner;
import static fr.minobot.core.vision.BannerCaptures.blank;
import static fr.minobot.core.vision.BannerCaptures.paint;
import static fr.minobot.core.vision.BannerCaptures.requestBox;
import static org.assertj.core.api.Assertions.assertThat;

/** Paints the request box where the game draws it ({@link BannerCaptures}) and checks what is read of it. */
class RequestDialogTest {

    @Test
    @DisplayName("the question in the box comes back as the request it asks")
    void readsTheRequest() {
        final var reader = new FakeTextReader().reading("Alpha t'invite à rejoindre son groupe.\nAcceptes-tu ?");

        assertThat(new RequestDialog(reader).request(requestBox()))
                .contains(new Request(Request.Kind.GROUP_INVITE, "Alpha"));
    }

    @Test
    @DisplayName("with no box up, one row of a frame is read: a region is a copy off the GPU")
    void readsARowOffAFrameWithNoBox() {
        final var frame = RecordingFrame.of(blank(2560, 1369));

        assertThat(new RequestDialog(new FakeTextReader().reading("anything")).request(frame)).isEmpty();
        assertThat(frame.regions).singleElement().satisfies(row -> assertThat(row.height()).isEqualTo(1));
    }

    @Test
    @DisplayName("with a box up, its title row, then its body, and nothing else of the frame are read")
    void readsTheBoxOffAFrame() {
        final var frame = RecordingFrame.of(requestBox());
        final var reader = new FakeTextReader().reading("Alpha t'invite à rejoindre son groupe.\nAcceptes-tu ?");

        assertThat(new RequestDialog(reader).request(frame)).isPresent();
        assertThat(frame.regions).hasSize(2);
        assertThat(frame.regions.getFirst().height()).as("the title row").isEqualTo(1);
        assertThat(frame.regions.getLast().width() * frame.regions.getLast().height()).as("the body, not the window")
                .isLessThan(frame.width() * frame.height() / 10);
    }

    @Test
    @DisplayName("a frame whose box cannot be read asks nothing, and the OCR is not asked")
    void readsNothingOfAnUnreadableFrame() {
        final var reader = new FakeTextReader().reading("Alpha t'invite à rejoindre son groupe.");

        assertThat(new RequestDialog(reader).request(RecordingFrame.unreadable(requestBox()))).isEmpty();
        assertThat(reader.images()).isEmpty();
    }

    @Test
    @DisplayName("only the question goes to the OCR, in the box's beige, clear of the title and the buttons")
    void handsTheOcrTheQuestion() {
        final var reader = new FakeTextReader().reading("Alpha te propose de faire un échange.");

        new RequestDialog(reader).request(requestBox());

        assertThat(reader.images()).singleElement().satisfies(image -> {
            assertThat(image.rgb(0, 0)).as("the margin").isEqualTo(BEIGE);
            assertThat(image.rgb(image.width() / 2, image.height() / 2)).as("the question").isEqualTo(INK);
            assertThat(image.pixels()).as("no title bar in it").doesNotContain(BannerCaptures.TITLE_BAR);
        });
    }

    @Test
    @DisplayName("no box, no OCR — the turn banner's beige is not a box")
    void readsNothingWithoutABox() {
        final var reader = new FakeTextReader().reading("Alpha t'invite à rejoindre son groupe.");

        assertThat(new RequestDialog(reader).request(blank(2560, 1369))).isEmpty();
        assertThat(new RequestDialog(reader).request(banner())).isEmpty();
        assertThat(reader.images()).isEmpty();
    }

    @Test
    @DisplayName("a box with no title bar over it — beige alone — is not read")
    void wantsTheTitleBar() {
        final var reader = new FakeTextReader().reading("Alpha t'invite à rejoindre son groupe.");
        final var capture = requestBox();
        paint(capture, 862, 358, 1699, 412, BEIGE);

        assertThat(new RequestDialog(reader).request(capture)).isEmpty();
    }

    @Test
    @DisplayName("a duel is asked in the same box")
    void readsADuel() {
        final var reader = new FakeTextReader().reading("Alpha te défie.\nAcceptes-tu ?");

        assertThat(new RequestDialog(reader).request(requestBox())).contains(new Request(Request.Kind.DUEL, "Alpha"));
    }

    @Test
    @DisplayName("a box that asks something else is no request")
    void readsNothingOfAnotherQuestion() {
        final var reader = new FakeTextReader().reading("Voulez-vous vraiment quitter ? Acceptes-tu ?");

        assertThat(new RequestDialog(reader).request(requestBox())).isEmpty();
    }

    @Test
    void readsNothingWhenTheOcrFails() {
        assertThat(new RequestDialog(new FakeTextReader().failing()).request(requestBox())).isEmpty();
    }
}
