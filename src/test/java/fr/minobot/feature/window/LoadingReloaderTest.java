package fr.minobot.feature.window;

import fr.minobot.app.TestConfigs;
import fr.minobot.core.ActivityLog;
import fr.minobot.core.WindowManager;
import fr.minobot.win32.Capture;
import fr.minobot.win32.FakeWindowApi;
import fr.minobot.win32.FakeWindowApi.PostedMessage;
import fr.minobot.win32.Win32;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** The game windows left on the loading error at launch, reloaded with {@code CTRL+R} in the background. */
class LoadingReloaderTest {

    private static final String GAME = "C:\\Games\\Dofus Retro\\Dofus Retro.exe";
    private static final int VK_R = 0x52;

    private final FakeWindowApi api = new FakeWindowApi();
    private final ActivityLog activity = new ActivityLog();
    private final AtomicLong clock = new AtomicLong();
    private final LoadingReloader reloader = new LoadingReloader(api,
            new WindowManager(api, TestConfigs.settings(Map.of())), activity, clock::get);

    /** The error as measured on a 2560x1369 window: the red link to its solutions, the orange log buttons. */
    private static Capture loadingError() {
        final var capture = new Capture(2560, 1369, new int[2560 * 1369]);
        paint(capture, 383, 1188, 820, 1212, 0xFF0000);
        paint(capture, 1922, 1243, 2165, 1281, 0xFF6100);
        return capture;
    }

    private static void paint(Capture capture, int left, int top, int right, int bottom, int rgb) {
        for (var y = top; y < bottom; y++) {
            Arrays.fill(capture.pixels(), y * capture.width() + left, y * capture.width() + right, rgb);
        }
    }

    private FakeWindowApi loading(long hwnd, Capture showing) {
        return api.withWindow(hwnd, "Dofus Retro").runningAs(hwnd, GAME).showing(hwnd, showing);
    }

    private long reloads(long hwnd) {
        return api.postedMessages().stream()
                .filter(message -> message.hwnd() == hwnd && message.message() == Win32.WM_KEYDOWN
                        && message.wparam() == VK_R)
                .count();
    }

    private void later(Duration by) {
        clock.addAndGet(by.toNanos());
    }

    @Test
    @DisplayName("presses CTRL+R in a window on the error, CONTROL held in its thread around the keys")
    void reloadsAWindowOnTheError() {
        loading(1, loadingError());

        reloader.sweep();

        assertThat(api.postedMessages()).extracting(PostedMessage::message, PostedMessage::wparam)
                .containsSubsequence(
                        tuple(FakeWindowApi.HELD, (long) Win32.VK_CONTROL),
                        tuple(Win32.WM_KEYDOWN, (long) Win32.VK_CONTROL),
                        tuple(Win32.WM_KEYDOWN, (long) VK_R),
                        tuple(Win32.WM_KEYUP, (long) VK_R),
                        tuple(Win32.WM_KEYUP, (long) Win32.VK_CONTROL),
                        tuple(FakeWindowApi.LET_GO, (long) Win32.VK_CONTROL));
        assertThat(api.foregroundWindow()).isEqualTo(Win32.NULL_HANDLE);
        assertThat(activity.recent()).hasSize(1);
    }

    @Test
    @DisplayName("leaves alone a window loading as it should, and one with a character in it")
    void leavesTheOthersAlone() {
        loading(1, new Capture(2560, 1369, new int[2560 * 1369]));
        api.withWindow(2, "Alpha - Dofus Retro v1.49.10").showing(2, loadingError());

        reloader.sweep();

        assertThat(api.postedMessages()).isEmpty();
    }

    @Test
    @DisplayName("looks again a few seconds after a reload, and reloads again a window still on the error")
    void retries() {
        loading(1, loadingError());
        reloader.sweep();

        later(LoadingReloader.RETRY.minusSeconds(1));
        reloader.sweep();
        assertThat(reloads(1)).isEqualTo(1);

        later(Duration.ofSeconds(1));
        reloader.sweep();
        assertThat(reloads(1)).isEqualTo(2);
    }

    @Test
    @DisplayName("gives up after a few reloads in vain, and says so once")
    void givesUp() {
        loading(1, loadingError());
        for (var sweep = 0; sweep < LoadingReloader.ATTEMPTS + 3; sweep++) {
            reloader.sweep();
            later(LoadingReloader.RETRY);
        }

        assertThat(reloads(1)).isEqualTo(LoadingReloader.ATTEMPTS);
        assertThat(activity.recent()).hasSize(LoadingReloader.ATTEMPTS + 1);
    }

    @Test
    @DisplayName("starts the count over for a window that reached a character since")
    void forgetsAWindowThatLoaded() {
        loading(1, loadingError());
        for (var sweep = 0; sweep < LoadingReloader.ATTEMPTS; sweep++) {
            reloader.sweep();
            later(LoadingReloader.RETRY);
        }
        api.withWindow(1, "Alpha - Dofus Retro v1.49.10");
        reloader.sweep();

        api.withWindow(1, "Dofus Retro");
        reloader.sweep();

        assertThat(reloads(1)).isEqualTo(LoadingReloader.ATTEMPTS + 1);
    }
}
