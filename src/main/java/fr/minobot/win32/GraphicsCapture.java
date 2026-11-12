package fr.minobot.win32;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongPredicate;

/**
 * The compositor's own frames of a window ({@code Windows.Graphics.Capture}), which is how
 * {@link User32#frame} reads the game: the window is not asked to paint itself again, as with
 * {@code PrintWindow}, and its frame stays on the GPU but for the regions read.
 *
 * <p>Measured on eight game windows in a fight ({@code tools.GraphicsCaptureProbe}): a look took 0.2 ms
 * instead of 21, a region the size of the turn banner 0.3 ms, and the vision read the same thing as off
 * {@code PrintWindow} on 1600 looks out of 1600. Windows lets the yellow border round a captured window
 * be dropped, and it is.
 *
 * <p>A capture session per window, opened the first time the window is read and kept while it lives. The
 * compositor hands each new frame to a {@code FrameArrived} handler of ours, which keeps the newest; a look
 * takes it. A window that draws nothing new delivers no frame, and the last one is still what it shows.
 *
 * <p>The WinRT slots and IIDs were read from {@code Windows.Graphics.winmd}. The D3D11 ones are plain COM,
 * absent from the metadata, and follow {@code d3d11.h}'s declaration order — checked by the probe, a
 * texture describing its own size and format right.
 *
 * <p><strong>Every call runs on one dedicated thread</strong>, as in {@link WindowsOcr}: it joins the
 * multithreaded apartment, and D3D11's immediate context is not thread-safe. The handler alone runs on the
 * compositor's threads.
 */
public final class GraphicsCapture implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GraphicsCapture.class);

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup COMBASE = SymbolLookup.libraryLookup("combase.dll", Arena.global());
    private static final SymbolLookup D3D11 = SymbolLookup.libraryLookup("d3d11.dll", Arena.global());
    private static final SymbolLookup DWMAPI = SymbolLookup.libraryLookup("dwmapi.dll", Arena.global());

    private static final int RO_INIT_MULTITHREADED = 1;

    private static final int D3D_DRIVER_TYPE_HARDWARE = 1;
    private static final int D3D11_CREATE_DEVICE_BGRA_SUPPORT = 0x20;
    private static final int D3D11_SDK_VERSION = 7;
    private static final int D3D11_USAGE_STAGING = 3;
    private static final int D3D11_CPU_ACCESS_READ = 0x20000;
    private static final int D3D11_MAP_READ = 1;
    /** {@code DXGI_FORMAT_B8G8R8A8_UNORM} and {@code DirectXPixelFormat.B8G8R8A8UIntNormalized} alike. */
    private static final int BGRA8 = 87;
    private static final int DWMWA_EXTENDED_FRAME_BOUNDS = 9;

    /**
     * One held by the look reading it, one waiting to be taken, one the compositor draws into. With two,
     * the compositor had nowhere to draw while one was held and one waited.
     */
    private static final int FRAME_BUFFERS = 3;

    /** A look is well under a millisecond; one that has not finished in this long is not going to be useful. */
    private static final long CALL_TIMEOUT_MS = 2_000;

    private static final MemorySegment IID_DXGI_DEVICE = guid("54ec77fa-1377-44e6-8c32-88fd5f44c84c");
    private static final MemorySegment IID_DIRECT3D_DEVICE = guid("a37624ab-8d5f-4650-9d3e-9eae3d9bc670");
    private static final MemorySegment IID_CAPTURE_ITEM_INTEROP = guid("3628e81b-3cac-4c60-b7f4-23ce0e0c3356");
    private static final MemorySegment IID_CAPTURE_ITEM = guid("79c3f95b-31f7-4ec2-a464-632ef5d30760");
    private static final MemorySegment IID_FRAME_POOL_STATICS2 = guid("589b103f-6bbc-5df5-a991-02e28b3b66d5");
    private static final MemorySegment IID_SESSION2 = guid("2c39ae40-7d2e-5044-804e-8b6799d4cf9e");
    private static final MemorySegment IID_SESSION3 = guid("f2cdd966-22ae-5ea1-9596-3a289344c3be");
    private static final MemorySegment IID_CLOSABLE = guid("30d5a829-7fa4-4026-83bb-d75bae4ea99e");
    private static final MemorySegment IID_DXGI_INTERFACE_ACCESS = guid("a9b3d012-3df2-4ee3-b8d1-8695f457d3c1");
    private static final MemorySegment IID_TEXTURE_2D = guid("6f15aaf2-d208-4e89-9ab4-489535d34f9c");

    // Vtable slots
    private static final int QUERY_INTERFACE = 0;
    private static final int RELEASE = 2;
    private static final int CAPTURE_ITEM_INTEROP_CREATE_FOR_WINDOW = 3;
    private static final int CAPTURE_ITEM_GET_SIZE = 7;
    private static final int FRAME_POOL_STATICS2_CREATE_FREE_THREADED = 6;
    private static final int FRAME_POOL_TRY_GET_NEXT_FRAME = 7;
    private static final int FRAME_POOL_ADD_FRAME_ARRIVED = 8;
    private static final int FRAME_POOL_REMOVE_FRAME_ARRIVED = 9;
    private static final int FRAME_POOL_CREATE_CAPTURE_SESSION = 10;
    private static final int FRAME_GET_SURFACE = 6;
    private static final int FRAME_GET_CONTENT_SIZE = 8;
    private static final int SESSION_START_CAPTURE = 6;
    private static final int SESSION2_PUT_IS_CURSOR_CAPTURE_ENABLED = 7;
    private static final int SESSION3_PUT_IS_BORDER_REQUIRED = 7;
    private static final int CLOSABLE_CLOSE = 6;
    private static final int DXGI_INTERFACE_ACCESS_GET_INTERFACE = 3;
    private static final int DEVICE_CREATE_TEXTURE_2D = 5;
    private static final int CONTEXT_MAP = 14;
    private static final int CONTEXT_UNMAP = 15;
    private static final int CONTEXT_COPY_SUBRESOURCE_REGION = 46;

    /** {@code SizeInt32 { INT32 Width; INT32 Height; }} — 8 bytes, passed by value in a register. */
    private static final MemoryLayout SIZE = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("width"), ValueLayout.JAVA_INT.withName("height"));
    /** {@code D3D11_TEXTURE2D_DESC}: eleven {@code UINT}s, {@code SampleDesc} being two of them. */
    private static final long TEXTURE_DESC_SIZE = 11 * 4;
    /** {@code D3D11_MAPPED_SUBRESOURCE { void* pData; UINT RowPitch; UINT DepthPitch; }} */
    private static final long MAPPED_SIZE = 16;
    /** {@code D3D11_BOX { UINT left, top, front, right, bottom, back; }} */
    private static final long BOX_SIZE = 6 * 4;
    /** {@code RECT}: four {@code LONG}s. */
    private static final long RECT_SIZE = 4 * 4;

    private static final MethodHandle RoInitialize = function(COMBASE, "RoInitialize",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle RoGetActivationFactory = function(COMBASE, "RoGetActivationFactory",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle WindowsCreateString = function(COMBASE, "WindowsCreateString",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle WindowsDeleteString = function(COMBASE, "WindowsDeleteString",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    /** {@code D3D11CreateDevice(adapter, driverType, software, flags, levels, levelCount, sdk, device**, level*, context**)} */
    private static final MethodHandle D3D11CreateDevice = function(D3D11, "D3D11CreateDevice",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle CreateDirect3D11DeviceFromDXGIDevice = function(D3D11,
            "CreateDirect3D11DeviceFromDXGIDevice",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    /** {@code HRESULT DwmGetWindowAttribute(HWND, DWORD attribute, void* value, DWORD size)} */
    private static final MethodHandle DwmGetWindowAttribute = function(DWMAPI, "DwmGetWindowAttribute",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    /*
     * One handle per method *shape*, as in WindowsOcr: the function pointer is read from the object's
     * vtable at each call and passed first, the object itself second.
     */
    /** {@code ULONG Release(this)}, {@code HRESULT StartCapture(this)} */
    private static final MethodHandle CALL_THIS = method(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    /** {@code HRESULT method(this, T* out)} */
    private static final MethodHandle CALL_OUT = method(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    /** {@code HRESULT method(this, A* in, T* out)} */
    private static final MethodHandle CALL_IN_OUT = method(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    /** {@code remove_FrameArrived(this, EventRegistrationToken)} — the token is an int64, by value. */
    private static final MethodHandle CALL_LONG = method(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    /** {@code HRESULT put_Flag(this, boolean)} — a WinRT boolean is one byte. */
    private static final MethodHandle CALL_BOOL = method(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE));
    /** {@code CreateForWindow(this, HWND, REFIID, void**)} */
    private static final MethodHandle CALL_CREATE_FOR_WINDOW = method(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    /** {@code CreateFreeThreaded(this, IDirect3DDevice*, DirectXPixelFormat, INT32 buffers, SizeInt32, pool**)} */
    private static final MethodHandle CALL_CREATE_FREE_THREADED = method(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, SIZE, ValueLayout.ADDRESS));
    /** {@code CreateTexture2D(this, const DESC*, const INITIAL_DATA*, ID3D11Texture2D**)} */
    private static final MethodHandle CALL_CREATE_TEXTURE_2D = method(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    /** {@code Map(this, resource, UINT subresource, D3D11_MAP, UINT flags, MAPPED_SUBRESOURCE*)} */
    private static final MethodHandle CALL_MAP = method(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    /** {@code void Unmap(this, resource, UINT subresource)} */
    private static final MethodHandle CALL_UNMAP = method(
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    /** {@code void CopySubresourceRegion(this, dst, UINT dstSub, UINT x, UINT y, UINT z, src, UINT srcSub, const BOX*)} */
    private static final MethodHandle CALL_COPY_SUBRESOURCE_REGION = method(
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    private final ExecutorService thread;
    private final LongPredicate alive;

    // Owned by the capture thread, and touched by no other.
    private MemorySegment device = MemorySegment.NULL;
    private MemorySegment context = MemorySegment.NULL;
    private MemorySegment winrtDevice = MemorySegment.NULL;
    private MemorySegment items = MemorySegment.NULL;
    private MemorySegment pools = MemorySegment.NULL;
    private final Map<Long, Session> sessions = new HashMap<>();

    private GraphicsCapture(LongPredicate alive) {
        this.alive = alive;
        this.thread = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("graphics-capture").factory());
    }

    /**
     * Starts a GPU device and the factories a capture is made with — empty on a Windows without
     * {@code Windows.Graphics.Capture}, or without a GPU to give a device.
     *
     * @param alive whether a window handle still names a window: the sessions of the others are closed
     */
    public static Optional<GraphicsCapture> start(LongPredicate alive) {
        final var capture = new GraphicsCapture(alive);
        try {
            if (capture.thread.submit(capture::initialize).get()) {
                return Optional.of(capture);
            }
        } catch (ExecutionException e) {
            log.warn("Windows.Graphics.Capture could not be started.", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        capture.close();
        return Optional.empty();
    }

    /**
     * The window's newest frame, cut to its client area — empty until the compositor delivered a first
     * one (the look after the window's session opens), and whenever a call is refused: the session is then
     * dropped, and opened anew at the next look.
     *
     * @param client the window's client area, on the screen
     */
    public Optional<Frame> frame(long hwnd, Rect client) {
        return onThread(() -> take(hwnd, client));
    }

    @Override
    public void close() {
        thread.submit(() -> {
            sessions.values().forEach(Session::close);
            sessions.clear();
            release(pools);
            release(items);
            release(winrtDevice);
            release(context);
            release(device);
        });
        thread.shutdown();
    }

    private <T> Optional<T> onThread(Callable<Optional<T>> call) {
        final var future = thread.submit(call);
        try {
            return future.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Reading a window's frame took longer than {} ms; dropped.", CALL_TIMEOUT_MS);
            return Optional.empty();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Win32Exception bug) {
                throw bug;
            }
            log.warn("Reading a window's frame failed.", e.getCause());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ on the capture thread

    private boolean initialize() {
        try (final var arena = Arena.ofConfined()) {
            final var joined = (int) RoInitialize.invokeExact(RO_INIT_MULTITHREADED);
            if (joined < 0) {
                log.warn("RoInitialize refused: 0x{}", Integer.toHexString(joined));
                return false;
            }
            final var deviceOut = arena.allocate(ValueLayout.ADDRESS);
            final var contextOut = arena.allocate(ValueLayout.ADDRESS);
            check((int) D3D11CreateDevice.invokeExact(MemorySegment.NULL, D3D_DRIVER_TYPE_HARDWARE, MemorySegment.NULL,
                    D3D11_CREATE_DEVICE_BGRA_SUPPORT, MemorySegment.NULL, 0, D3D11_SDK_VERSION,
                    deviceOut, MemorySegment.NULL, contextOut), "D3D11CreateDevice");
            device = deviceOut.get(ValueLayout.ADDRESS, 0);
            context = contextOut.get(ValueLayout.ADDRESS, 0);

            final var dxgiDevice = query(arena, device, IID_DXGI_DEVICE, "IDXGIDevice");
            final var out = arena.allocate(ValueLayout.ADDRESS);
            final MemorySegment inspectable;
            try {
                check((int) CreateDirect3D11DeviceFromDXGIDevice.invokeExact(dxgiDevice, out),
                        "CreateDirect3D11DeviceFromDXGIDevice");
                inspectable = out.get(ValueLayout.ADDRESS, 0);
            } finally {
                release(dxgiDevice);
            }
            try {
                winrtDevice = query(arena, inspectable, IID_DIRECT3D_DEVICE, "IDirect3DDevice");
            } finally {
                release(inspectable);
            }

            items = factory(arena, "Windows.Graphics.Capture.GraphicsCaptureItem", IID_CAPTURE_ITEM_INTEROP);
            pools = factory(arena, "Windows.Graphics.Capture.Direct3D11CaptureFramePool", IID_FRAME_POOL_STATICS2);
            return true;
        } catch (Refused refused) {
            log.warn("Windows.Graphics.Capture is not available: {}", refused.getMessage());
            return false;
        } catch (Throwable t) {
            throw failure("start", t);
        }
    }

    private Optional<Frame> take(long hwnd, Rect client) {
        sessions.entrySet().removeIf(entry -> {
            final var gone = !alive.test(entry.getKey());
            if (gone) {
                entry.getValue().close();
            }
            return gone;
        });

        var session = sessions.get(hwnd);
        try {
            if (session == null) {
                session = new Session(hwnd);
                sessions.put(hwnd, session);
                session.open();
            }
            if (!session.take()) {
                return Optional.empty();
            }
            if (session.contentWidth != session.poolWidth || session.contentHeight != session.poolHeight) {
                // Resized: the pool's buffers are the old size. A session of the new one opens next look.
                throw new Refused("A window resized", 0);
            }
            final var bounds = session.frameBounds();
            final var left = client.left() - bounds.left();
            final var top = client.top() - bounds.top();
            if (left < 0 || top < 0 || left + client.width() > session.contentWidth
                    || top + client.height() > session.contentHeight) {
                return Optional.empty();
            }
            return Optional.of(new CompositorFrame(session, session.generation, left, top, client.width(), client.height()));
        } catch (Refused refused) {
            log.debug("Capture of window {} dropped: {}", hwnd, refused.getMessage());
            sessions.remove(hwnd).close();
            return Optional.empty();
        } catch (Throwable t) {
            throw failure("frame", t);
        }
    }

    /** A frame as the compositor delivered it, cut to the window's client area and read a region at a time. */
    private final class CompositorFrame implements Frame {

        private final Session session;
        private final long generation;
        private final int left;
        private final int top;
        private final int width;
        private final int height;

        CompositorFrame(Session session, long generation, int left, int top, int width, int height) {
            this.session = session;
            this.generation = generation;
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public Optional<Capture> region(int left, int top, int right, int bottom) {
            if (left < 0 || top < 0 || right > width || bottom > height || left >= right || top >= bottom) {
                throw new IndexOutOfBoundsException(
                        "[" + left + ", " + top + ", " + right + ", " + bottom + ") is not inside a " + width + "x" + height + " frame");
            }
            return onThread(() -> session.copy(generation,
                    this.left + left, this.top + top, this.left + right, this.top + bottom));
        }
    }

    /**
     * One window being captured.
     *
     * <p>Its frames arrive on the compositor's threads ({@link #swapIn}) while the capture thread takes
     * them, hence the lock on the one waiting — and on {@link #closed}, so that no handler call outlives
     * the pool it reads from.
     */
    private final class Session {

        private final long hwnd;
        private MemorySegment item = MemorySegment.NULL;
        private MemorySegment pool = MemorySegment.NULL;
        private MemorySegment session = MemorySegment.NULL;
        private MemorySegment handler = MemorySegment.NULL;
        private long token;
        private int poolWidth;
        private int poolHeight;

        // Guarded by this.
        private MemorySegment latest = MemorySegment.NULL;
        private boolean closed;

        // The capture thread's.
        private MemorySegment frame = MemorySegment.NULL;
        private MemorySegment texture = MemorySegment.NULL;
        private int contentWidth;
        private int contentHeight;
        /** Moves at every take: a frame handed out before reads nothing once a newer one is. */
        private long generation;
        private MemorySegment staging = MemorySegment.NULL;
        private int stagingWidth;
        private int stagingHeight;

        Session(long hwnd) {
            this.hwnd = hwnd;
        }

        /** Its frames flow from now on, whether or not they are taken. */
        void open() throws Throwable {
            try (final var arena = Arena.ofConfined()) {
                final var out = arena.allocate(ValueLayout.ADDRESS);
                check((int) CALL_CREATE_FOR_WINDOW.invokeExact(vtable(items, CAPTURE_ITEM_INTEROP_CREATE_FOR_WINDOW),
                        items, hwnd, IID_CAPTURE_ITEM, out), "CreateForWindow");
                item = out.get(ValueLayout.ADDRESS, 0);

                final var size = arena.allocate(SIZE);
                check((int) CALL_OUT.invokeExact(vtable(item, CAPTURE_ITEM_GET_SIZE), item, size), "get_Size");
                poolWidth = size.get(ValueLayout.JAVA_INT, 0);
                poolHeight = size.get(ValueLayout.JAVA_INT, 4);

                check((int) CALL_CREATE_FREE_THREADED.invokeExact(vtable(pools, FRAME_POOL_STATICS2_CREATE_FREE_THREADED),
                        pools, winrtDevice, BGRA8, FRAME_BUFFERS, size, out), "CreateFreeThreaded");
                pool = out.get(ValueLayout.ADDRESS, 0);

                check((int) CALL_IN_OUT.invokeExact(vtable(pool, FRAME_POOL_CREATE_CAPTURE_SESSION), pool, item, out),
                        "CreateCaptureSession");
                session = out.get(ValueLayout.ADDRESS, 0);

                // Both are later additions to Windows; a session without them still captures.
                setFlag(arena, IID_SESSION2, SESSION2_PUT_IS_CURSOR_CAPTURE_ENABLED, false);
                setFlag(arena, IID_SESSION3, SESSION3_PUT_IS_BORDER_REQUIRED, false);

                listen(arena);
                check((int) CALL_THIS.invokeExact(vtable(session, SESSION_START_CAPTURE), session), "StartCapture");
            }
        }

        private void setFlag(Arena arena, MemorySegment iid, int slot, boolean value) throws Throwable {
            final var out = arena.allocate(ValueLayout.ADDRESS);
            if ((int) CALL_IN_OUT.invokeExact(vtable(session, QUERY_INTERFACE), session, iid, out) < 0) {
                return;
            }
            final var facet = out.get(ValueLayout.ADDRESS, 0);
            try {
                final var ignored = (int) CALL_BOOL.invokeExact(vtable(facet, slot), facet, (byte) (value ? 1 : 0));
            } finally {
                release(facet);
            }
        }

        /**
         * Listens for the pool's frames, before the capture starts.
         *
         * <p>Polled, the pool goes stale: once its buffers are full the compositor drops every newer frame
         * until one is taken, so a frame taken every 100 ms was the one drawn just after the last take —
         * 50 to 90 ms old, measured. Swapped in as each arrives, the frame waiting is always the newest.
         */
        private void listen(Arena arena) throws Throwable {
            handler = newHandler();
            LISTENING.put(handler.address(), this);
            final var token = arena.allocate(ValueLayout.JAVA_LONG);
            check((int) CALL_IN_OUT.invokeExact(vtable(pool, FRAME_POOL_ADD_FRAME_ARRIVED), pool, handler, token),
                    "add_FrameArrived");
            this.token = token.get(ValueLayout.JAVA_LONG, 0);
        }

        /** On a thread of the compositor's. */
        void swapIn() throws Throwable {
            try (final var arena = Arena.ofConfined()) {
                final var out = arena.allocate(ValueLayout.ADDRESS);
                synchronized (this) {
                    if (closed) {
                        return;
                    }
                    while ((int) CALL_OUT.invokeExact(vtable(pool, FRAME_POOL_TRY_GET_NEXT_FRAME), pool, out) >= 0) {
                        final var next = out.get(ValueLayout.ADDRESS, 0);
                        if (next.equals(MemorySegment.NULL)) {
                            return;
                        }
                        dispose(latest);
                        latest = next;
                    }
                }
            }
        }

        /** Takes the newest frame delivered, if any — whether there is now a frame to read. */
        boolean take() throws Throwable {
            final MemorySegment next;
            synchronized (this) {
                next = latest;
                latest = MemorySegment.NULL;
            }
            generation++;
            if (!next.equals(MemorySegment.NULL)) {
                dropFrame();
                frame = next;
                try (final var arena = Arena.ofConfined()) {
                    final var size = arena.allocate(SIZE);
                    check((int) CALL_OUT.invokeExact(vtable(frame, FRAME_GET_CONTENT_SIZE), frame, size), "get_ContentSize");
                    contentWidth = size.get(ValueLayout.JAVA_INT, 0);
                    contentHeight = size.get(ValueLayout.JAVA_INT, 4);
                    texture = textureOf(arena, frame);
                }
            }
            return !texture.equals(MemorySegment.NULL);
        }

        /** Where the frame's origin is on the screen: a window's frame is its visible bounds, shadow excluded. */
        Rect frameBounds() throws Throwable {
            try (final var arena = Arena.ofConfined()) {
                final var rect = arena.allocate(RECT_SIZE);
                check((int) DwmGetWindowAttribute.invokeExact(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, rect, (int) RECT_SIZE),
                        "DwmGetWindowAttribute");
                return new Rect(rect.get(ValueLayout.JAVA_INT, 0), rect.get(ValueLayout.JAVA_INT, 4),
                        rect.get(ValueLayout.JAVA_INT, 8), rect.get(ValueLayout.JAVA_INT, 12));
            }
        }

        /** A region of the frame, copied off the GPU into a texture the CPU may read, then into memory. */
        Optional<Capture> copy(long generation, int left, int top, int right, int bottom) {
            if (generation != this.generation || texture.equals(MemorySegment.NULL)) {
                return Optional.empty();
            }
            final var width = right - left;
            final var height = bottom - top;
            try (final var arena = Arena.ofConfined()) {
                final var target = stagingOf(arena, width, height);
                final var box = arena.allocate(BOX_SIZE);
                final int[] edges = {left, top, 0, right, bottom, 1};
                for (var i = 0; i < edges.length; i++) {
                    box.setAtIndex(ValueLayout.JAVA_INT, i, edges[i]);
                }
                CALL_COPY_SUBRESOURCE_REGION.invokeExact(vtable(context, CONTEXT_COPY_SUBRESOURCE_REGION), context,
                        target, 0, 0, 0, 0, texture, 0, box);

                final var mapped = arena.allocate(MAPPED_SIZE);
                check((int) CALL_MAP.invokeExact(vtable(context, CONTEXT_MAP), context, target, 0, D3D11_MAP_READ, 0, mapped),
                        "Map");
                try {
                    final var rowPitch = mapped.get(ValueLayout.JAVA_INT, 8);
                    final var bits = mapped.get(ValueLayout.ADDRESS, 0).reinterpret((long) rowPitch * height);
                    final var pixels = new int[width * height];
                    for (var y = 0; y < height; y++) {
                        MemorySegment.copy(bits, ValueLayout.JAVA_INT, (long) y * rowPitch, pixels, y * width, width);
                    }
                    // The alpha byte is the window's, and a Capture's pixels are 0xRRGGBB.
                    for (var i = 0; i < pixels.length; i++) {
                        pixels[i] &= 0xFFFFFF;
                    }
                    return Optional.of(new Capture(width, height, pixels));
                } finally {
                    CALL_UNMAP.invokeExact(vtable(context, CONTEXT_UNMAP), context, target, 0);
                }
            } catch (Refused refused) {
                log.debug("A region of window {} could not be read: {}", hwnd, refused.getMessage());
                return Optional.empty();
            } catch (Throwable t) {
                throw failure("region", t);
            }
        }

        /** A texture the CPU may read, at least this large, kept from one copy to the next. */
        private MemorySegment stagingOf(Arena arena, int width, int height) throws Throwable {
            if (!staging.equals(MemorySegment.NULL) && stagingWidth >= width && stagingHeight >= height) {
                return staging;
            }
            release(staging);
            staging = MemorySegment.NULL;
            final var grownWidth = Math.max(width, stagingWidth);
            final var grownHeight = Math.max(height, stagingHeight);
            final var desc = arena.allocate(TEXTURE_DESC_SIZE);
            final int[] fields = {grownWidth, grownHeight, 1, 1, BGRA8, 1, 0, D3D11_USAGE_STAGING, 0, D3D11_CPU_ACCESS_READ, 0};
            for (var i = 0; i < fields.length; i++) {
                desc.setAtIndex(ValueLayout.JAVA_INT, i, fields[i]);
            }
            final var out = arena.allocate(ValueLayout.ADDRESS);
            check((int) CALL_CREATE_TEXTURE_2D.invokeExact(vtable(device, DEVICE_CREATE_TEXTURE_2D), device, desc,
                    MemorySegment.NULL, out), "CreateTexture2D");
            staging = out.get(ValueLayout.ADDRESS, 0);
            stagingWidth = grownWidth;
            stagingHeight = grownHeight;
            return staging;
        }

        private void dropFrame() throws Throwable {
            release(texture);
            texture = MemorySegment.NULL;
            dispose(frame);
            frame = MemorySegment.NULL;
        }

        /** Gives everything back; the handler is no longer called once this returns. */
        void close() {
            try {
                synchronized (this) {
                    closed = true;
                    dispose(latest);
                    latest = MemorySegment.NULL;
                }
                if (!handler.equals(MemorySegment.NULL)) {
                    LISTENING.remove(handler.address());
                    if (!pool.equals(MemorySegment.NULL)) {
                        final var ignored = (int) CALL_LONG.invokeExact(vtable(pool, FRAME_POOL_REMOVE_FRAME_ARRIVED), pool, token);
                    }
                }
                dropFrame();
                release(staging);
                closeWinRt(session);
                release(session);
                closeWinRt(pool);
                release(pool);
                release(item);
            } catch (Throwable t) {
                throw failure("close", t);
            }
        }
    }

    /** The surface a frame is, as the D3D11 texture it is underneath. */
    private static MemorySegment textureOf(Arena arena, MemorySegment frame) throws Throwable {
        final var out = arena.allocate(ValueLayout.ADDRESS);
        check((int) CALL_OUT.invokeExact(vtable(frame, FRAME_GET_SURFACE), frame, out), "get_Surface");
        final var surface = out.get(ValueLayout.ADDRESS, 0);
        try {
            final var access = query(arena, surface, IID_DXGI_INTERFACE_ACCESS, "IDirect3DDxgiInterfaceAccess");
            try {
                check((int) CALL_IN_OUT.invokeExact(vtable(access, DXGI_INTERFACE_ACCESS_GET_INTERFACE), access,
                        IID_TEXTURE_2D, out), "GetInterface(ID3D11Texture2D)");
                return out.get(ValueLayout.ADDRESS, 0);
            } finally {
                release(access);
            }
        } finally {
            release(surface);
        }
    }

    /** A frame goes back to its pool once closed; released only, the compositor could not reuse it. */
    private static void dispose(MemorySegment frame) throws Throwable {
        if (!frame.equals(MemorySegment.NULL)) {
            closeWinRt(frame);
            release(frame);
        }
    }

    // ------------------------------------------------------------------ the FrameArrived handler

    /**
     * {@code TypedEventHandler<Direct3D11CaptureFramePool, Object>}. A generic's IID is in no metadata:
     * WinRT derives it from the instantiation's signature, and the event source asks the handler for
     * exactly that one.
     */
    static final UUID FRAME_ARRIVED_HANDLER = parameterizedIid("pinterface({9de1c534-6ae1-11e0-84e1-18a905bcc53f};"
            + "rc(Windows.Graphics.Capture.Direct3D11CaptureFramePool;{24eb6d22-1975-422e-82e7-780dbd8ddf24});"
            + "cinterface(IInspectable))");
    private static final MemorySegment IID_FRAME_ARRIVED_HANDLER = guid(FRAME_ARRIVED_HANDLER.toString());
    private static final MemorySegment IID_UNKNOWN = guid("00000000-0000-0000-c000-000000000046");
    /** Says the handler may be called from any apartment: without it, COM would try to marshal it. */
    private static final MemorySegment IID_AGILE_OBJECT = guid("94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90");
    private static final int S_OK = 0;
    private static final int E_NOINTERFACE = 0x80004002;

    /** The handler is called with nothing but its own address to say which session it serves. */
    private static final Map<Long, Session> LISTENING = new ConcurrentHashMap<>();

    /** The handler's four methods: the three of {@code IUnknown}, then {@code Invoke}. Every handler shares them. */
    private static final MemorySegment HANDLER_VTABLE;

    static {
        try {
            final var lookup = MethodHandles.lookup();
            final var queryInterface = LINKER.upcallStub(
                    lookup.findStatic(GraphicsCapture.class, "handlerQueryInterface", MethodType.methodType(
                            int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    Arena.global());
            final var count = LINKER.upcallStub(
                    lookup.findStatic(GraphicsCapture.class, "handlerCount",
                            MethodType.methodType(int.class, MemorySegment.class)),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                    Arena.global());
            final var invoke = LINKER.upcallStub(
                    lookup.findStatic(GraphicsCapture.class, "handlerInvoke", MethodType.methodType(
                            int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    Arena.global());
            HANDLER_VTABLE = Arena.global().allocate(ValueLayout.ADDRESS, 4);
            HANDLER_VTABLE.setAtIndex(ValueLayout.ADDRESS, 0, queryInterface);
            HANDLER_VTABLE.setAtIndex(ValueLayout.ADDRESS, 1, count);
            HANDLER_VTABLE.setAtIndex(ValueLayout.ADDRESS, 2, count);
            HANDLER_VTABLE.setAtIndex(ValueLayout.ADDRESS, 3, invoke);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * A handler object: one word, pointing at the shared vtable. It is never freed — a call the compositor
     * has already begun may still reach it after the session closed — and is one pointer a session.
     */
    private static MemorySegment newHandler() {
        final var handler = Arena.global().allocate(ValueLayout.ADDRESS);
        handler.set(ValueLayout.ADDRESS, 0, HANDLER_VTABLE);
        return handler;
    }

    private static int handlerQueryInterface(MemorySegment self, MemorySegment iid, MemorySegment out) {
        final var asked = iid.reinterpret(16);
        final var target = out.reinterpret(ValueLayout.ADDRESS.byteSize());
        if (asked.mismatch(IID_UNKNOWN) == -1 || asked.mismatch(IID_AGILE_OBJECT) == -1
                || asked.mismatch(IID_FRAME_ARRIVED_HANDLER) == -1) {
            target.set(ValueLayout.ADDRESS, 0, self);
            return S_OK;
        }
        target.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
        return E_NOINTERFACE;
    }

    /** {@code AddRef} and {@code Release}: the session owns its handler's life, so there is no count to keep. */
    private static int handlerCount(MemorySegment self) {
        return 1;
    }

    /** On a thread of the compositor's: anything thrown out of an upcall brings the JVM down. */
    private static int handlerInvoke(MemorySegment self, MemorySegment sender, MemorySegment args) {
        try {
            final var session = LISTENING.get(self.address());
            if (session != null) {
                session.swapIn();
            }
        } catch (Throwable t) {
            log.debug("A frame could not be taken in.", t);
        }
        return S_OK;
    }

    /** A name-based UUID (SHA-1) of the signature under WinRT's own namespace, as WinRT derives a generic's IID. */
    static UUID parameterizedIid(String signature) {
        try {
            final var sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(new byte[]{0x11, (byte) 0xf4, 0x7a, (byte) 0xd5, 0x7b, 0x73, 0x42, (byte) 0xc0,
                    (byte) 0xab, (byte) 0xae, (byte) 0x87, (byte) 0x8b, 0x1e, 0x16, (byte) 0xad, (byte) 0xee});
            final var hash = sha1.digest(signature.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            final var bytes = ByteBuffer.wrap(hash, 0, 16);
            return new UUID(bytes.getLong(), bytes.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JDK has SHA-1", e);
        }
    }

    // ------------------------------------------------------------------ COM plumbing

    /** From any thread: the compositor's closes the frames it replaces. */
    private static void closeWinRt(MemorySegment closable) throws Throwable {
        if (closable.equals(MemorySegment.NULL)) {
            return;
        }
        try (final var arena = Arena.ofConfined()) {
            final var out = arena.allocate(ValueLayout.ADDRESS);
            if ((int) CALL_IN_OUT.invokeExact(vtable(closable, QUERY_INTERFACE), closable, IID_CLOSABLE, out) < 0) {
                return;
            }
            final var facet = out.get(ValueLayout.ADDRESS, 0);
            try {
                final var ignored = (int) CALL_THIS.invokeExact(vtable(facet, CLOSABLE_CLOSE), facet);
            } finally {
                release(facet);
            }
        }
    }

    private static MemorySegment query(Arena arena, MemorySegment object, MemorySegment iid, String name) throws Throwable {
        final var out = arena.allocate(ValueLayout.ADDRESS);
        check((int) CALL_IN_OUT.invokeExact(vtable(object, QUERY_INTERFACE), object, iid, out), "QueryInterface(" + name + ")");
        return out.get(ValueLayout.ADDRESS, 0);
    }

    private static MemorySegment factory(Arena arena, String className, MemorySegment iid) throws Throwable {
        final var utf16 = arena.allocateFrom(className, StandardCharsets.UTF_16LE);
        final var nameOut = arena.allocate(ValueLayout.ADDRESS);
        check((int) WindowsCreateString.invokeExact(utf16, className.length(), nameOut), "WindowsCreateString");
        final var name = nameOut.get(ValueLayout.ADDRESS, 0);
        try {
            final var out = arena.allocate(ValueLayout.ADDRESS);
            check((int) RoGetActivationFactory.invokeExact(name, iid, out), "RoGetActivationFactory(" + className + ")");
            return out.get(ValueLayout.ADDRESS, 0);
        } finally {
            final var ignored = (int) WindowsDeleteString.invokeExact(name);
        }
    }

    /** The function pointer in a COM object's vtable — the object's first word points at the table. */
    private static MemorySegment vtable(MemorySegment object, int slot) {
        final var pointer = ValueLayout.ADDRESS.byteSize();
        return object.reinterpret(pointer)
                .get(ValueLayout.ADDRESS, 0)
                .reinterpret(pointer * (slot + 1))
                .getAtIndex(ValueLayout.ADDRESS, slot);
    }

    private static void release(MemorySegment object) {
        if (object.equals(MemorySegment.NULL)) {
            return;
        }
        try {
            final var ignored = (int) CALL_THIS.invokeExact(vtable(object, RELEASE), object);
        } catch (Throwable t) {
            throw failure("Release", t);
        }
    }

    /** A failed {@code HRESULT} is a refusal: the call is tolerated, and its session dropped. */
    private static void check(int hresult, String call) throws Refused {
        if (hresult < 0) {
            throw new Refused(call, hresult);
        }
    }

    private static final class Refused extends Exception {

        Refused(String call, int hresult) {
            super(hresult == 0 ? call : call + " failed: 0x" + Integer.toHexString(hresult), null, false, false);
        }
    }

    /** A {@code GUID}: one 32-bit and two 16-bit fields in native order, then eight bytes as written. */
    private static MemorySegment guid(String text) {
        final var uuid = UUID.fromString(text);
        final var segment = Arena.global().allocate(16, 4);
        final var high = uuid.getMostSignificantBits();
        final var low = uuid.getLeastSignificantBits();
        segment.set(ValueLayout.JAVA_INT, 0, (int) (high >>> 32));
        segment.set(ValueLayout.JAVA_SHORT, 4, (short) (high >>> 16));
        segment.set(ValueLayout.JAVA_SHORT, 6, (short) high);
        for (var i = 0; i < 8; i++) {
            segment.set(ValueLayout.JAVA_BYTE, 8 + i, (byte) (low >>> (56 - 8 * i)));
        }
        return segment;
    }

    private static MethodHandle function(SymbolLookup library, String name, FunctionDescriptor descriptor) {
        final var symbol = library.find(name).orElseThrow(() -> new IllegalStateException("No export named " + name));
        return LINKER.downcallHandle(symbol, descriptor);
    }

    private static MethodHandle method(FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(descriptor);
    }

    /** As in {@link User32}: a {@code Throwable} out of {@code invokeExact} is a bug, never an HRESULT. */
    private static Win32Exception failure(String step, Throwable cause) {
        if (cause instanceof Error error) {
            throw error;
        }
        if (cause instanceof Win32Exception already) {
            return already;
        }
        return new Win32Exception("Native call during graphics capture " + step + " failed", cause);
    }
}
