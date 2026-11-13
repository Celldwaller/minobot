package fr.minobot.win32;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory desktop.
 *
 * <p>This is the whole point of {@link WindowApi}: the window ordering, the monitor filtering and
 * the hotkey matching can be driven from a test, on any OS, with no game running
 */
public final class FakeWindowApi implements WindowApi {

    /**
     * The desktop itself, and it is read from another thread than the one that writes it: the overlay's
     * follower polls the window list and each window's area while the test opens, moves and closes them.
     * Synchronized rather than concurrent so the enumeration order — which several tests read as the
     * order Windows would have given — is still the order they were added in.
     */
    private final Map<Long, String> titles = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<Long, String> executables = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<Long, Rect> bounds = Collections.synchronizedMap(new LinkedHashMap<>());

    private final Map<Long, Capture> captures = new ConcurrentHashMap<>();

    private final Set<Long> minimized =ConcurrentHashMap.newKeySet();
    private final Set<Long> maximized = ConcurrentHashMap.newKeySet();
    private final Set<Long> hidden = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> monitors = new LinkedHashMap<>();
    private final Map<Long, Long> parents = new LinkedHashMap<>();
    private final Set<Integer> keysDown = ConcurrentHashMap.newKeySet();
    private final List<PostedMessage> posted = new CopyOnWriteArrayList<>();
    private final List<Long> shown = new CopyOnWriteArrayList<>();
    private final List<Long> focused = new CopyOnWriteArrayList<>();

    private boolean readableFlashCount = true;

    private long foreground = Win32.NULL_HANDLE;
    private long underCursor = Win32.NULL_HANDLE;
    private Point cursor = new Point(0, 0);

    /** Adds a visible window on monitor 1, in the order the fake will enumerate it. */
    public FakeWindowApi withWindow(long hwnd, String title) {
        titles.put(hwnd, title);
        monitors.put(hwnd, 1L);
        return this;
    }

    /**
     * Puts a process behind a window — the whole path, {@code "C:\\...\\Dofus Retro.exe"}. A window
     * with none reads back the empty string, as one whose process cannot be opened does on the real
     * desktop.
     */
    public FakeWindowApi runningAs(long hwnd, String executablePath) {
        executables.put(hwnd, executablePath);
        return this;
    }

    public FakeWindowApi onMonitor(long hwnd, long monitor) {
        monitors.put(hwnd, monitor);
        return this;
    }

    /** Destroys a window: its handle no longer designates anything. */
    public FakeWindowApi close(long hwnd) {
        titles.remove(hwnd);
        minimized.remove(hwnd);
        maximized.remove(hwnd);
        hidden.remove(hwnd);
        return this;
    }

    public FakeWindowApi minimize(long hwnd) {
        minimized.add(hwnd);
        return this;
    }

    public FakeWindowApi withForeground(long hwnd) {
        foreground = hwnd;
        return this;
    }

    public FakeWindowApi withCursor(Point position) {
        cursor = position;
        return this;
    }

    /** The window {@code WindowFromPoint} reports — a child window is a legitimate answer. */
    public FakeWindowApi withWindowUnderCursor(long hwnd) {
        underCursor = hwnd;
        return this;
    }

    /** Declares a child window, so the multi-clicker's walk back up to the game window is exercised. */
    public FakeWindowApi withParent(long child, long parent) {
        parents.put(child, parent);
        return this;
    }

    /** Presses a key, as the polling loop will see it on its next tick. */
    public void press(int virtualKey) {
        keysDown.add(virtualKey);
    }

    public void release(int virtualKey) {
        keysDown.remove(virtualKey);
    }

    @Override
    public List<Long> topLevelWindows() {
        // A synchronized map guards each call, never an iteration: the lock is the map itself, and this
        // is the one place the fake walks it while another thread may be adding a window.
        synchronized (titles) {
            return new ArrayList<>(titles.keySet());
        }
    }

    @Override
    public String windowText(long hwnd) {
        return titles.getOrDefault(hwnd, "");
    }

    @Override
    public boolean isWindowVisible(long hwnd) {
        return titles.containsKey(hwnd) && !hidden.contains(hwnd);
    }

    @Override
    public String executablePath(long hwnd) {
        return executables.getOrDefault(hwnd, "");
    }

    @Override
    public boolean isIconic(long hwnd) {
        return minimized.contains(hwnd);
    }

    @Override
    public boolean isWindow(long hwnd) {
        return titles.containsKey(hwnd);
    }

    @Override
    public void showWindow(long hwnd, int command) {
        switch (command) {
            case Win32.SW_HIDE -> hidden.add(hwnd);
            case Win32.SW_MAXIMIZE -> {
                minimized.remove(hwnd);
                hidden.remove(hwnd);
                maximized.add(hwnd);
                markShown(hwnd);
            }
            case Win32.SW_RESTORE -> {
                minimized.remove(hwnd);
                maximized.remove(hwnd);
                hidden.remove(hwnd);
                markShown(hwnd);
            }
            case Win32.SW_SHOW -> {
                hidden.remove(hwnd);
                markShown(hwnd);
            }
            default -> throw new IllegalArgumentException("Unsupported ShowWindow command: " + command);
        }
    }

    /** Records the window's first appearance on screen — the reorder brings each back more than once
     *  (a SW_SHOW then a SW_MAXIMIZE), and the taskbar order is fixed by that first showing. */
    private void markShown(long hwnd) {
        if (!shown.contains(hwnd)) {
            shown.add(hwnd);
        }
    }

    /** The windows brought back on screen, in order — the taskbar order the reorder feature builds. */
    public List<Long> shownWindows() {
        return List.copyOf(shown);
    }

    public boolean isHidden(long hwnd) {
        return hidden.contains(hwnd);
    }

    public boolean isMaximized(long hwnd) {
        return maximized.contains(hwnd);
    }

    @Override
    public boolean setForegroundWindow(long hwnd) {
        if (!isWindow(hwnd)) {
            return false;
        }
        foreground = hwnd;
        focused.add(hwnd);
        return true;
    }

    /** The windows focused so far, in order — what a cycling or invitation sequence really did. */
    public List<Long> focusedWindows() {
        return List.copyOf(focused);
    }

    @Override
    public void bringWindowToTop(long hwnd) {
        // Z-order is not modelled: nothing here reads it.
    }

    @Override
    public long foregroundWindow() {
        return foreground;
    }

    /** The windows that will turn down the next message posted to them, once each. */
    private final Set<Long> refuseNextPost = ConcurrentHashMap.newKeySet();

    /**
     * Makes a window refuse the next message posted to it — what a busy one does on the real desktop,
     * and the case the multi-clicker's retry exists for. Nothing is recorded for a refused post: it
     * leaves no message in any queue.
     */
    public FakeWindowApi refusingTheNextPostTo(long hwnd) {
        refuseNextPost.add(hwnd);
        return this;
    }

    @Override
    public boolean postMessage(long hwnd, int message, long wparam, long lparam) {
        if (refuseNextPost.remove(hwnd)) {
            return false;
        }
        posted.add(new PostedMessage(hwnd, message, wparam, lparam));
        return isWindow(hwnd);
    }

    /** Every message posted so far — the multi-clicker's whole output. */
    public List<PostedMessage> postedMessages() {
        return List.copyOf(posted);
    }

    public record PostedMessage(long hwnd, int message, long wparam, long lparam) {
    }

    /** The client area is modelled as the screen shifted by (hwnd * 10), so the maths is checkable. */
    @Override
    public Optional<Point> screenToClient(long hwnd, Point screen) {
        if (!isWindow(hwnd)) {
            return Optional.empty();
        }
        int offset = (int) (hwnd * 10);
        return Optional.of(new Point(screen.x() - offset, screen.y() - offset));
    }

    @Override
    public Optional<Point> clientToScreen(long hwnd, Point client) {
        if (!isWindow(hwnd)) {
            return Optional.empty();
        }
        int offset = (int) (hwnd * 10);
        return Optional.of(new Point(client.x() + offset, client.y() + offset));
    }

    /**
     * The frame Windows draws around a window, modelled so that a test can tell "over the game" from
     * "over the title bar" — which is the whole difference between the client area and the bounds.
     */
    private static final int TITLE_BAR = 30;
    private static final int BORDER = 8;

    /**
     * The game inside the frame. A window is 800x600 at (hwnd * 100, hwnd * 100) unless a test says
     * otherwise — so two windows are never in the same place, and the overlay's anchor can be told
     * apart from its neighbour's.
     */
    @Override
    public Optional<Rect> clientArea(long hwnd) {
        if (!isWindow(hwnd)) {
            return Optional.empty();
        }

        final var origin = (int) (hwnd * 100);
        final var window = bounds.getOrDefault(hwnd, new Rect(origin, origin, origin + 800, origin + 600));

        return Optional.of(new Rect(
                window.left() + BORDER,
                window.top() + TITLE_BAR,
                window.right() - BORDER,
                window.bottom() - BORDER));
    }

    /**
     * What the window shows, as a test painted it. A window with none, a minimized one or a closed one
     * captures to nothing, as on the real desktop.
     */
    @Override
    public Optional<Capture> capture(long hwnd) {
        if (!isWindow(hwnd) || isIconic(hwnd)) {
            return Optional.empty();
        }
        return Optional.ofNullable(captures.get(hwnd));
    }

    /** Paints what the next {@link #capture} of the window will see — and every one after it. */
    public FakeWindowApi showing(long hwnd, Capture capture) {
        captures.put(hwnd, capture);
        return this;
    }

    /** Puts a window somewhere precise — on another monitor, say. Bounds, frame included. */
    public FakeWindowApi withBounds(long hwnd, Rect window) {
        bounds.put(hwnd, window);
        return this;
    }

    /** Moves or resizes a window after the fact, as a player dragging it by its title bar would. */
    public void moveTo(long hwnd, Rect window) {
        bounds.put(hwnd, window);
    }

    @Override
    public long windowFromPoint(Point screen) {
        return underCursor;
    }

    @Override
    public long parentWindow(long hwnd) {
        return parents.getOrDefault(hwnd, Win32.NULL_HANDLE);
    }

    @Override
    public boolean isKeyDown(int virtualKey) {
        return keysDown.contains(virtualKey);
    }

    @Override
    public Optional<Point> cursorPosition() {
        return Optional.of(cursor);
    }

    @Override
    public long monitorFromWindow(long hwnd) {
        return monitors.getOrDefault(hwnd, Win32.NULL_HANDLE);
    }

    private final List<Long> flashStops = new CopyOnWriteArrayList<>();

    /** Windows' own default: seven blinks of the taskbar button on a refused foreground request. */
    private int flashCount = 7;

    @Override
    public void stopFlashing(long hwnd) {
        flashStops.add(hwnd);
    }

    /**
     * How many times a window was told to drop its orange.
     *
     * <p>The taskbar is not simulated — but <em>when</em> the orange is cleared is the whole question:
     * Windows ignores the request while the button is still blinking.
     */
    public long flashStopsFor(long hwnd) {
        return flashStops.stream().filter(stopped -> stopped == hwnd).count();
    }

    /** Makes the setting unreadable, as a locked-down machine would. */
    public FakeWindowApi withUnreadableFlashCount() {
        readableFlashCount = false;
        return this;
    }

    @Override
    public OptionalInt foregroundFlashCount() {
        return readableFlashCount ? OptionalInt.of(flashCount) : OptionalInt.empty();
    }

    @Override
    public boolean setForegroundFlashCount(int count) {
        if (!readableFlashCount) {
            return false; // a machine that will not tell will not be told either
        }
        flashCount = count;
        return true;
    }
}
