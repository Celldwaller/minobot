package fr.minobot.feature.window;

import fr.minobot.feature.Features;
import fr.minobot.win32.FakeWindowApi;
import fr.minobot.win32.Win32;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The launcher's new windows, maximized once as they show up. */
class WindowMaximizerTest {

    private static final String GAME = "C:\\Games\\Dofus Retro\\Dofus Retro.exe";

    @Test
    @DisplayName("maximizes a game window that opens after Minobot, even at the login screen")
    void maximizesANewWindow() {
        final var api = new FakeWindowApi();
        final var maximizer = new Features(api, Map.of()).maximizer();
        maximizer.sweep();

        api.withWindow(1, "Dofus Retro v1.48.18").runningAs(1, GAME);
        maximizer.sweep();

        assertThat(api.isMaximized(1)).isTrue();
    }

    @Test
    @DisplayName("leaves the windows already open when Minobot starts as the player arranged them")
    void leavesTheWindowsFoundAtStartup() {
        final var api = new FakeWindowApi().withWindow(1, "Alpha - Dofus Retro v1.48.18");
        final var maximizer = new Features(api, Map.of()).maximizer();

        maximizer.sweep();
        maximizer.sweep();

        assertThat(api.isMaximized(1)).isFalse();
    }

    @Test
    @DisplayName("maximizes a window once: neither logging in nor the taskbar reorder makes it new again")
    void maximizesOnce() {
        final var api = new FakeWindowApi();
        final var maximizer = new Features(api, Map.of()).maximizer();
        maximizer.sweep();
        api.withWindow(1, "Dofus Retro v1.48.18").runningAs(1, GAME);
        maximizer.sweep();
        api.showWindow(1, Win32.SW_RESTORE);

        api.withWindow(1, "Alpha - Dofus Retro v1.48.18");
        api.showWindow(1, Win32.SW_HIDE);
        maximizer.sweep();
        api.showWindow(1, Win32.SW_SHOW);
        maximizer.sweep();

        assertThat(api.isMaximized(1)).isFalse();
    }

    @Test
    @DisplayName("a handle Windows reuses for a new window is a new window")
    void forgetsClosedWindows() {
        final var api = new FakeWindowApi();
        final var maximizer = new Features(api, Map.of()).maximizer();
        maximizer.sweep();
        api.withWindow(1, "Alpha - Dofus Retro v1.48.18");
        maximizer.sweep();

        api.close(1);
        maximizer.sweep();
        api.withWindow(1, "Bravo - Dofus Retro v1.48.18");
        maximizer.sweep();

        assertThat(api.isMaximized(1)).isTrue();
    }

    @Test
    @DisplayName("ignores windows that are not the game's")
    void ignoresOtherWindows() {
        final var api = new FakeWindowApi();
        final var maximizer = new Features(api, Map.of()).maximizer();
        maximizer.sweep();

        api.withWindow(9, "Dofus Retro wiki - Mozilla Firefox");
        maximizer.sweep();

        assertThat(api.isMaximized(9)).isFalse();
    }

    @Test
    @DisplayName("does not pull back up a new window the player minimized first")
    void leavesAMinimizedWindowDown() {
        final var api = new FakeWindowApi();
        final var maximizer = new Features(api, Map.of()).maximizer();
        maximizer.sweep();

        api.withWindow(1, "Alpha - Dofus Retro v1.48.18").minimize(1);
        maximizer.sweep();

        assertThat(api.isIconic(1)).isTrue();
        assertThat(api.isMaximized(1)).isFalse();
    }
}
