package fr.minobot.feature.window;

import fr.minobot.app.TestConfigs;
import fr.minobot.feature.Features;
import fr.minobot.win32.FakeWindowApi;
import fr.minobot.win32.FakeWindowApi.PostedMessage;
import fr.minobot.win32.Point;
import fr.minobot.win32.Win32;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the multi-clicker actually posts, and where.
 *
 * <p>The fake models a window's client area as the screen shifted by {@code hwnd * 10}: a click at
 * screen (100, 100) is client (90, 90) in window 1 and client (80, 80) in window 2. So a test can
 * tell the two translations apart — sending the <em>same client</em> position to every window (right)
 * from sending the <em>same screen</em> position (wrong, the windows are not stacked on top of each
 * other).
 */
class MultiWindowClickerTest {

    private static final Point CURSOR = new Point(100, 100);

    /** Alphabetical by default: Alpha (1), Bravo (2), Delta (3). */
    private FakeWindowApi desktop() {
        return new FakeWindowApi()
                .withWindow(1, "Alpha - Dofus")
                .withWindow(2, "Bravo - Dofus")
                .withWindow(3, "Delta - Dofus")
                .withCursor(CURSOR);
    }

    /** The down/up pair of a left click at a client position. */
    private static List<PostedMessage> leftClickOn(long hwnd, int x, int y) {
        final var lparam = Win32.makeLParam(x, y);
        return List.of(
                new PostedMessage(hwnd, Win32.WM_LBUTTONDOWN, Win32.MK_LBUTTON, lparam, false),
                new PostedMessage(hwnd, Win32.WM_LBUTTONUP, 0, lparam, false));
    }

    /**
     * What a single window was posted, in order. The clicks are now fired in parallel — one virtual
     * thread per character — so the windows interleave in the global log; only the down→up order
     * <em>within</em> a window is fixed, and that is what each test pins down, window by window.
     */
    private static List<PostedMessage> messagesTo(FakeWindowApi api, long hwnd) {
        return api.postedMessages().stream().filter(message -> message.hwnd() == hwnd).toList();
    }


    @Nested
    class Positioning {

        @Test
        @DisplayName("every window is clicked at the same position in its own client area")
        void replaysTheClickAtTheEquivalentClientPosition() {
            final var api = desktop().withForeground(1).withWindowUnderCursor(1);

            new Features(api, Map.of()).clicker().clickEveryCharacter(CURSOR);

            // The cursor is at client (90, 90) of window 1; the others are clicked at their own
            // client (90, 90) — not at screen (100, 100), which would land somewhere else in them.
            assertThat(messagesTo(api, 1)).containsExactlyElementsOf(leftClickOn(1, 90, 90));
            assertThat(messagesTo(api, 2)).containsExactlyElementsOf(leftClickOn(2, 90, 90));
            assertThat(messagesTo(api, 3)).containsExactlyElementsOf(leftClickOn(3, 90, 90));
        }

        @Test
        @DisplayName("a click on the game's child window resolves to the game window")
        void walksUpFromAChildWindow() {
            final var api = desktop()
                    .withForeground(2)
                    .withWindowUnderCursor(77) // the render surface, not a top-level window
                    .withParent(77, 2);

            new Features(api, Map.of()).clicker().clickEveryCharacter(CURSOR);

            // Resolved to window 2, whose client position for the cursor is (80, 80).
            assertThat(messagesTo(api, 1)).containsExactlyElementsOf(leftClickOn(1, 80, 80));
            assertThat(messagesTo(api, 2)).containsExactlyElementsOf(leftClickOn(2, 80, 80));
            assertThat(messagesTo(api, 3)).containsExactlyElementsOf(leftClickOn(3, 80, 80));
        }

        @Test
        @DisplayName("a click outside the game is replayed nowhere")
        void postsNothingWhenTheClickDidNotLandOnAGameWindow() {
            final var api = desktop().withWindowUnderCursor(Win32.NULL_HANDLE);

            new Features(api, Map.of()).clicker().clickEveryCharacter(CURSOR);

            // There is no in-game spot to replay: the player clicked on the desktop or on another
            // application. Translating the screen point per window would fire every character at
            // whatever sits there in their own view, which the player never aimed at.
            assertThat(api.postedMessages()).isEmpty();
        }
    }

    @Nested
    class Targets {

        @Test
        @DisplayName("the foreground window is clicked too: the hotkey is X1, not a click")
        void clicksTheForegroundWindowAsWell() {
            final var api = desktop().withForeground(2).withWindowUnderCursor(2);

            new Features(api, Map.of()).clicker().clickEveryCharacter(CURSOR);

            assertThat(api.postedMessages()).extracting(PostedMessage::hwnd).contains(2L);
        }

        @Test
        void skipsTheMinimizedWindows() {
            final var api = desktop().minimize(3).withForeground(1).withWindowUnderCursor(1);

            new Features(api, Map.of()).clicker().clickEveryCharacter(CURSOR);

            assertThat(api.postedMessages()).extracting(PostedMessage::hwnd).containsOnly(1L, 2L);
        }

        @Test
        @DisplayName("multiclick_exclude keeps a character out of the click")
        void skipsTheExcludedWindows() {
            final var api = desktop().withForeground(1).withWindowUnderCursor(1);

            new Features(api, Map.of("multiclick_exclude", List.of("delta")))
                    .clicker().clickEveryCharacter(CURSOR);

            assertThat(api.postedMessages()).extracting(PostedMessage::hwnd).containsOnly(1L, 2L);
        }

        @Test
        @DisplayName("excluding a character takes effect on the next click, with no restart")
        void followsALiveChangeOfTheExclusions() {
            final var api = desktop().withForeground(1).withWindowUnderCursor(1);
            final var features = new Features(api, Map.of());
            final var clicker = features.clicker();

            features.settings().update(config -> config.withMulticlickExclude(List.of("Bravo")));
            clicker.clickEveryCharacter(CURSOR);

            assertThat(api.postedMessages()).extracting(PostedMessage::hwnd)
                    .as("Bravo was excluded after the clicker was built, and must still be spared")
                    .containsOnly(1L, 3L);
        }

        @Test
        @DisplayName("a character who logged in since the last click is clicked too")
        void clicksAWindowOpenedSinceTheLastSweep() {
            final var api = desktop().withForeground(1).withWindowUnderCursor(1);
            final var clicker = new Features(api, Map.of()).clicker();
            clicker.clickEveryCharacter(CURSOR); // the sweep now holds Alpha, Bravo and Delta

            api.withWindow(4, "Echo - Dofus"); // logs in, well inside the thirty-second interval
            clicker.clickEveryCharacter(CURSOR);

            // Echo is in no configured order, so it sorts last: the character a stale sweep drops is
            // exactly the one the player would report as the window the click never reaches.
            assertThat(messagesTo(api, 4)).containsExactlyElementsOf(leftClickOn(4, 90, 90));
        }

        @Test
        @DisplayName("the player's window keeps the focus: no window is ever raised")
        void neverStealsTheFocus() {
            final var api = desktop().withForeground(1).withWindowUnderCursor(1);

            new Features(api, Map.of()).clicker().clickEveryCharacter(CURSOR);

            assertThat(api.foregroundWindow()).isEqualTo(1);
        }
    }

    @Nested
    class TaskbarOrange {

        @Test
        @DisplayName("the clicked windows keep being cleared after the click, not only at it")
        void clearsTheOrangeAfterTheGameHasRaisedIt() throws InterruptedException {
            final var api = desktop().withForeground(1).withWindowUnderCursor(1);

            new Features(api, Map.of()).clicker().clickEveryCharacter(CURSOR);

            // The game has not even drained the click yet, and Windows ignores the request while the
            // button is blinking: what is cleared now is cleared for nothing. What counts is what comes
            // after — a single call at click time is exactly the bug this feature had.
            final var atClickTime = api.flashStopsFor(2);
            Thread.sleep(300);

            assertThat(api.flashStopsFor(2)).isGreaterThan(atClickTime);
        }

        @Test
        @DisplayName("a window that refused the first click is swept like the others")
        void clearsTheOrangeOfAWindowThatNeededTheRetry() throws InterruptedException {
            final var api = desktop().withForeground(1).withWindowUnderCursor(1)
                    .refusingTheNextPostTo(3);

            new Features(api, Map.of()).clicker().clickEveryCharacter(CURSOR);
            Thread.sleep(300);

            // Delta turned the first post down and was clicked on the retry, a beat after the others.
            // A wait that gave up before that beat would leave it out of the swept list — clicked, and
            // orange for good, which is the one window a player notices.
            assertThat(messagesTo(api, 3)).containsExactlyElementsOf(leftClickOn(3, 90, 90));
            assertThat(api.flashStopsFor(3)).isPositive();
        }

        @Test
        @DisplayName("a window that was not clicked is left alone")
        void leavesTheUnclickedWindowsAlone() throws InterruptedException {
            final var api = desktop().minimize(3).withForeground(1).withWindowUnderCursor(1);

            new Features(api, Map.of("multiclick_exclude", List.of("Bravo")))
                    .clicker().clickEveryCharacter(CURSOR);
            Thread.sleep(300);

            // Bravo (2) is excluded, Delta (3) is minimized: neither was clicked, so neither can be
            // orange, and neither is worth a native call.
            assertThat(api.flashStopsFor(1)).isPositive();
            assertThat(api.flashStopsFor(2)).isZero();
            assertThat(api.flashStopsFor(3)).isZero();
        }
    }

    @Nested
    class AttentionReset {

        @Test
        @DisplayName("visits every window back to front, then hands the focus back to the leader")
        void visitsEveryWindowAndReturnsToTheLeader() {
            final var api = desktop().withForeground(1);

            new Features(api, Map.of("characters", TestConfigs.characters("Delta", "Bravo", "Alpha")))
                    .clicker().resetCharacters();

            // Configured order is Delta (3), Bravo (2), Alpha (1): visited back to front, then the
            // focus goes back to the leader, Delta — which is where the player was playing. Alpha (1)
            // is not raised: it already had the focus, so it cannot have been flashing.
            assertThat(api.focusedWindows()).containsExactly(2L, 3L);
            assertThat(api.foregroundWindow()).isEqualTo(3);
        }

        @Test
        @DisplayName("with no configured order, the leader is still the first window, not the last")
        void picksTheLeaderFromTheConfiguredOrder() {
            final var api = desktop().withForeground(3);

            new Features(api, Map.of()).clicker().resetCharacters();

            // Alphabetical for want of a configured order: the leader is Alpha, and the reversal is a
            // real one — sorting by rank would leave the list untouched, every window sharing a rank.
            // Delta (3) is not raised: the player was already on it.
            assertThat(api.focusedWindows()).containsExactly(2L, 1L);
            assertThat(api.foregroundWindow()).isEqualTo(1);
        }
    }
}
