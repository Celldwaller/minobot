package fr.minobot.win32;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the FFM binding against the live {@code user32.dll}.
 *
 * <p>These assert on the shape of what Windows answers, not on any particular window being open, so
 * they hold whatever is running on the machine. They are the only thing standing between a
 * mis-declared {@code FunctionDescriptor} and a crash at runtime — a wrong signature does not fail
 * to compile, it corrupts the stack.
 */
@EnabledOnOs(OS.WINDOWS)
class User32Test {

    private final WindowApi api = User32.instance();

    /** A visible, titled, non-minimized window — whatever the machine happens to be showing. */
    private long anyVisibleWindow() {
        return api.topLevelWindows().stream()
                .filter(api::isWindowVisible)
                .filter(hwnd -> !api.isIconic(hwnd))
                .filter(hwnd -> !api.windowText(hwnd).isBlank())
                .findFirst()
                .orElse(Win32.NULL_HANDLE);
    }

    @Test
    @DisplayName("EnumWindows walks the desktop through an FFM upcall")
    void enumeratesTopLevelWindows() {
        final var windows = api.topLevelWindows();

        assertThat(windows).as("a Windows desktop always has top-level windows").isNotEmpty();
        assertThat(windows.stream().anyMatch(hwnd -> api.isWindowVisible(hwnd) && !api.windowText(hwnd).isBlank())).as("at least one visible window should have a title").isTrue();
    }

    @Test
    @DisplayName("a title comes back decoded, not truncated at the first NUL byte")
    void readsWindowTitles() {
        final var hwnd = anyVisibleWindow();
        assumeTrue(hwnd != Win32.NULL_HANDLE, "no visible titled window on this desktop");

        final var title = api.windowText(hwnd);

        assertThat(title)
                .isNotBlank()
                .doesNotContain("\0");
    }

    @Test
    @DisplayName("the null handle is answered, not crashed on")
    void toleratesTheNullHandle() {
        assertThat(api.isWindow(Win32.NULL_HANDLE)).isFalse();
        assertThat(api.isWindowVisible(Win32.NULL_HANDLE)).isFalse();
        assertThat(api.windowText(Win32.NULL_HANDLE)).isEmpty();
        assertThat(api.executablePath(Win32.NULL_HANDLE))
                .as("no process behind no window: empty, not a crash on OpenProcess(0)")
                .isEmpty();
    }

    @Test
    @DisplayName("a window resolves to its process's executable — three native calls that must line up")
    void readsTheExecutableBehindAWindow() {
        final var hwnd = anyVisibleWindow();
        assumeTrue(hwnd != Win32.NULL_HANDLE, "no visible titled window on this desktop");

        final var path = api.executablePath(hwnd);

        // A mis-declared OpenProcess or QueryFullProcessImageNameW would corrupt the call rather than
        // merely come back blank: a live window belongs to a real process with a real path on disk.
        // We do not assert a ".exe" suffix — a CI runner's foreground process (e.g. an agent host) can
        // report an extension-less image path, and the absolute path is already proof the calls lined up.
        assertThat(path)
                .as("GetWindowThreadProcessId → OpenProcess → QueryFullProcessImageNameW, all lined up")
                .isNotBlank()
                .contains("\\");
    }

    @Test
    void reportsTheForegroundWindow() {
        long hwnd = api.foregroundWindow();
        assumeTrue(hwnd != Win32.NULL_HANDLE, "no window holds the focus (headless session?)");

        assertThat(api.isWindow(hwnd)).isTrue();
    }

    @Test
    @DisplayName("ScreenToClient and ClientToScreen are inverses of one another")
    void convertsCoordinatesBothWays() {
        long hwnd = anyVisibleWindow();
        assumeTrue(hwnd != Win32.NULL_HANDLE, "no visible titled window on this desktop");

        final var screen = new Point(300, 400);
        final var client = api.screenToClient(hwnd, screen);
        assumeTrue(client.isPresent(), "the window vanished mid-test");

        assertThat(api.clientToScreen(hwnd, client.get())).contains(screen);
    }

    @Test
    @DisplayName("a client coordinate outside the window comes back negative, and survives the trip")
    void convertsCoordinatesOutsideTheClientArea() {
        final var hwnd = anyVisibleWindow();
        assumeTrue(hwnd != Win32.NULL_HANDLE, "no visible titled window on this desktop");

        // The far top-left of the virtual desktop is above and left of any normal window.
        final var screen = new Point(-4000, -4000);
        final var client = api.screenToClient(hwnd, screen);
        assumeTrue(client.isPresent());

        assertThat(api.clientToScreen(hwnd, client.get())).as("the round trip must hold for the negative coordinates that makeLParam has to pack").contains(screen);
    }

    @Test
    @DisplayName("GetClientRect fills the RECT it is handed, and it comes back in screen coordinates")
    void readsTheClientAreaOfAWindow() {
        final var hwnd = anyVisibleWindow();
        assumeTrue(hwnd != Win32.NULL_HANDLE, "no visible titled window on this desktop");

        final var area = api.clientArea(hwnd);
        assumeTrue(area.isPresent(), "the window vanished mid-test");

        // A mis-laid-out RECT would come back with its fields shuffled, which this catches: a real
        // window's client area is never empty, and its right edge is never left of its left one.
        assertThat(area.get().width()).isPositive();
        assertThat(area.get().height()).isPositive();

        // And it must be on the screen, not at the (0, 0) that GetClientRect answers on its own: the
        // ClientToScreen that follows it is the whole reason the overlay lands on the right monitor.
        assertThat(api.screenToClient(hwnd, area.get().center()))
                .as("the middle of the client area, converted back, is inside the client area")
                .hasValueSatisfying(client -> assertThat(client.x()).isPositive());
    }

    @Test
    @DisplayName("the client area of a dead handle comes back empty, not as zeroes")
    void reportsNoClientAreaForAStaleHandle() {
        assertThat(api.clientArea(Win32.NULL_HANDLE)).isEmpty();
    }

    @Test
    @DisplayName("PrintWindow paints a window into a DIB section the size of its client area")
    void capturesAWindow() {
        final var hwnd = anyVisibleWindow();
        assumeTrue(hwnd != Win32.NULL_HANDLE, "no visible titled window on this desktop");
        final var area = api.clientArea(hwnd);
        assumeTrue(area.isPresent(), "the window vanished mid-test");

        final var capture = api.capture(hwnd);
        assumeTrue(capture.isPresent(), "Windows refused to capture " + api.windowText(hwnd));

        // A mis-laid-out BITMAPINFO would come back the wrong size, or crash in CreateDIBSection.
        assertThat(capture.get().width()).isEqualTo(area.get().width());
        assertThat(capture.get().height()).isEqualTo(area.get().height());
    }

    @Test
    void capturesNothingOfADeadHandle() {
        assertThat(api.capture(Win32.NULL_HANDLE)).isEmpty();
    }

    @Test
    @DisplayName("WindowFromPoint takes its POINT by value — 8 bytes in a register on Win64")
    void findsTheWindowUnderTheCursor() {
        final var cursor = api.cursorPosition();
        assumeTrue(cursor.isPresent(), "no cursor (headless session?)");

        final var hwnd = api.windowFromPoint(cursor.get());
        assumeTrue(hwnd != Win32.NULL_HANDLE, "the cursor sits over no window");

        assertThat(api.isWindow(hwnd)).isTrue();
    }

    @Test
    void walksUpToTheTopLevelWindow() {
        final var hwnd = anyVisibleWindow();
        assumeTrue(hwnd != Win32.NULL_HANDLE, "no visible titled window on this desktop");

        assertThat(api.parentWindow(hwnd)).as("a top-level window has no parent").isZero();
    }

    @Test
    void locatesTheMonitorOfAWindow() {
        final var hwnd = anyVisibleWindow();
        assumeTrue(hwnd != Win32.NULL_HANDLE, "no visible titled window on this desktop");

        final var monitor = api.monitorFromWindow(hwnd);

        assertThat(monitor).as("MONITOR_DEFAULTTONEAREST always yields a monitor").isNotZero();
        assertThat(api.monitorFromWindow(hwnd)).as("the same window must map to the same monitor").isEqualTo(monitor);
    }

    @Test
    @DisplayName("polling a key that no keyboard has reports it as up")
    void pollsKeyState() {
        final var vkF13 = 0x7C;

        assertThat(api.isKeyDown(vkF13)).isFalse();
    }

    @Test
    @DisplayName("FlashWindowEx accepts the FLASHWINFO struct we lay out")
    void stopsTaskbarFlashing() {
        final var hwnd = anyVisibleWindow();
        assumeTrue(hwnd != Win32.NULL_HANDLE, "no visible titled window on this desktop");

        // A bad cbSize or a mis-aligned hwnd field would corrupt the call, not merely do nothing.
        assertDoesNotThrow(() -> api.stopFlashing(hwnd));
    }

    @Test
    @DisplayName("SystemParametersInfo reads back the taskbar flash count of this session")
    void readsTheForegroundFlashCount() {
        // Read-only: the write is exercised by FlashSuppressorTest, on the fake — setting it here
        // would change the session of whoever runs the tests. A wrong FFM signature would throw, and
        // a pvParam Windows would not write into comes back empty.
        assertThat(api.foregroundFlashCount()).as("Windows always has a flash count").isPresent();
    }
}
