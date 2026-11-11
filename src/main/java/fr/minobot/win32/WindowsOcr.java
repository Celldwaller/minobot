package fr.minobot.win32;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The real {@link TextReader}: the OCR engine built into Windows 10 and 11 ({@code Windows.Media.Ocr}),
 * called through the FFM API like {@link User32} — no library, nothing to install, and the languages are
 * those Windows already has.
 *
 * <p>The engine is a <strong>WinRT</strong> API, which has no exported functions to look up: it is a set
 * of COM objects. {@code combase.dll} hands out a class's <em>factory</em> by name, and from there every
 * call goes through the object's table of function pointers — slot 0 to 2 are {@code IUnknown}'s
 * ({@code QueryInterface}, {@code AddRef}, {@code Release}), 3 to 5 {@code IInspectable}'s, and an
 * interface's own methods start at 6, in the order of its metadata. Every slot and IID below was read
 * from the Windows metadata itself, not written from memory: a wrong slot does not fail, it calls
 * another method with the wrong arguments.
 *
 * <p>Microsoft documents the engine for packaged apps; it works unpackaged, which is what Minobot is —
 * measured, not assumed.
 *
 * <p><strong>Every call runs on one dedicated thread.</strong> COM objects belong to the apartment of
 * the thread that joined it ({@code RoInitialize}), and a virtual thread hops between carriers; one
 * platform thread joins the multithreaded apartment once, holds the engine, and serves every read.
 */
public final class WindowsOcr implements TextReader, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WindowsOcr.class);

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup COMBASE = SymbolLookup.libraryLookup("combase.dll", Arena.global());

    private static final int RO_INIT_MULTITHREADED = 1;
    private static final int S_OK = 0;

    /** {@code BitmapPixelFormat.Bgra8}: one byte each of blue, green, red, alpha — a little-endian ARGB int. */
    private static final int BITMAP_PIXEL_FORMAT_BGRA8 = 87;

    // AsyncStatus
    private static final int ASYNC_STARTED = 0;
    private static final int ASYNC_COMPLETED = 1;

    /** A read is a few milliseconds; one that has not finished in this long is not going to be useful. */
    private static final long READ_TIMEOUT_MS = 2_000;

    private static final String OCR_ENGINE = "Windows.Media.Ocr.OcrEngine";
    private static final String SOFTWARE_BITMAP = "Windows.Graphics.Imaging.SoftwareBitmap";
    private static final String CRYPTOGRAPHIC_BUFFER = "Windows.Security.Cryptography.CryptographicBuffer";

    private static final MemorySegment IID_OCR_ENGINE_STATICS = guid("5bffa85a-3384-3540-9940-699120d428a8");
    private static final MemorySegment IID_SOFTWARE_BITMAP_STATICS = guid("df0385db-672f-4a9d-806e-c2442f343e86");
    private static final MemorySegment IID_CRYPTOGRAPHIC_BUFFER_STATICS = guid("320b7e22-3cb0-4cdf-8663-1d28910065eb");
    private static final MemorySegment IID_ASYNC_INFO = guid("00000036-0000-0000-c000-000000000046");

    // Vtable slots
    private static final int QUERY_INTERFACE = 0;
    private static final int RELEASE = 2;
    private static final int OCR_ENGINE_STATICS_TRY_CREATE_FROM_USER_PROFILE_LANGUAGES = 10;
    private static final int OCR_ENGINE_RECOGNIZE_ASYNC = 6;
    private static final int OCR_RESULT_GET_TEXT = 8;
    private static final int SOFTWARE_BITMAP_STATICS_CREATE_COPY_FROM_BUFFER = 9;
    private static final int CRYPTOGRAPHIC_BUFFER_STATICS_CREATE_FROM_BYTE_ARRAY = 9;
    private static final int ASYNC_INFO_GET_STATUS = 7;
    private static final int ASYNC_OPERATION_GET_RESULTS = 8;

    private static final MethodHandle RoInitialize = combase("RoInitialize",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    /** {@code HRESULT RoGetActivationFactory(HSTRING classId, REFIID iid, void** factory)} */
    private static final MethodHandle RoGetActivationFactory = combase("RoGetActivationFactory",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    /** {@code HRESULT WindowsCreateString(PCWSTR, UINT32 length, HSTRING*)} */
    private static final MethodHandle WindowsCreateString = combase("WindowsCreateString",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle WindowsDeleteString = combase("WindowsDeleteString",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    /** {@code PCWSTR WindowsGetStringRawBuffer(HSTRING, UINT32* length)} */
    private static final MethodHandle WindowsGetStringRawBuffer = combase("WindowsGetStringRawBuffer",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /*
     * One handle per method *shape*: the function pointer is read from the object's vtable at each call
     * and passed first, the object itself second — every COM method takes its object as `this`.
     */
    /** {@code ULONG Release(this)} */
    private static final MethodHandle CALL_THIS = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    /** {@code HRESULT method(this, T* out)} */
    private static final MethodHandle CALL_OUT = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    /** {@code HRESULT method(this, A* in, T* out)} — {@code QueryInterface}, {@code RecognizeAsync} */
    private static final MethodHandle CALL_IN_OUT = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    /** {@code HRESULT CreateFromByteArray(this, UINT32 length, BYTE* bytes, IBuffer** out)} */
    private static final MethodHandle CALL_CREATE_FROM_BYTE_ARRAY = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    /** {@code HRESULT CreateCopyFromBuffer(this, IBuffer*, BitmapPixelFormat, INT32 w, INT32 h, SoftwareBitmap** out)} */
    private static final MethodHandle CALL_CREATE_COPY_FROM_BUFFER = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    private final ExecutorService thread;

    // Owned by the OCR thread, and touched by no other.
    private MemorySegment engine = MemorySegment.NULL;
    private MemorySegment bitmaps = MemorySegment.NULL;
    private MemorySegment buffers = MemorySegment.NULL;

    private WindowsOcr() {
        this.thread = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("ocr").factory());
    }

    /**
     * Starts the engine in the languages of the user's profile — empty when Windows has no OCR language
     * for any of them, or no OCR at all.
     */
    public static Optional<WindowsOcr> start() {
        final var ocr = new WindowsOcr();
        try {
            if (ocr.thread.submit(ocr::initialize).get()) {
                return Optional.of(ocr);
            }
        } catch (ExecutionException e) {
            log.warn("The Windows OCR engine could not be started.", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ocr.close();
        return Optional.empty();
    }

    @Override
    public Optional<String> read(Capture image) {
        final var future = thread.submit(() -> recognize(image));
        try {
            return future.get(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("OCR took longer than {} ms; dropped.", READ_TIMEOUT_MS);
            return Optional.empty();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Win32Exception bug) {
                throw bug;
            }
            log.warn("OCR failed.", e.getCause());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        thread.submit(() -> {
            release(engine);
            release(bitmaps);
            release(buffers);
        });
        thread.shutdown();
    }

    // ------------------------------------------------------------------ on the OCR thread

    private boolean initialize() {
        try (final var arena = Arena.ofConfined()) {
            final var joined = (int) RoInitialize.invokeExact(RO_INIT_MULTITHREADED);
            if (joined < 0) {
                log.warn("RoInitialize refused: 0x{}", Integer.toHexString(joined));
                return false;
            }

            final var engines = factory(arena, OCR_ENGINE, IID_OCR_ENGINE_STATICS);
            bitmaps = factory(arena, SOFTWARE_BITMAP, IID_SOFTWARE_BITMAP_STATICS);
            buffers = factory(arena, CRYPTOGRAPHIC_BUFFER, IID_CRYPTOGRAPHIC_BUFFER_STATICS);
            if (engines.equals(MemorySegment.NULL) || bitmaps.equals(MemorySegment.NULL) || buffers.equals(MemorySegment.NULL)) {
                release(engines);
                return false;
            }

            try {
                final var out = arena.allocate(ValueLayout.ADDRESS);
                final var created = (int) CALL_OUT.invokeExact(
                        method(engines, OCR_ENGINE_STATICS_TRY_CREATE_FROM_USER_PROFILE_LANGUAGES), engines, out);
                // S_OK with a null engine is the documented answer to "no language of yours is supported".
                engine = created == S_OK ? out.get(ValueLayout.ADDRESS, 0) : MemorySegment.NULL;
            } finally {
                release(engines);
            }
            return !engine.equals(MemorySegment.NULL);
        } catch (Throwable t) {
            throw failure("initialize", t);
        }
    }

    /**
     * The image becomes a byte buffer, the buffer a {@code SoftwareBitmap}, the bitmap an asynchronous
     * recognition, and its result a string — four COM objects, each released on the way out.
     *
     * <p>The recognition is polled rather than awaited: being told it completed means handing Windows a
     * COM object of our own to call back, and a read is over in a few milliseconds anyway.
     */
    private Optional<String> recognize(Capture image) {
        try (final var arena = Arena.ofConfined()) {
            final var out = arena.allocate(ValueLayout.ADDRESS);

            final var pixels = arena.allocate((long) image.pixels().length * Integer.BYTES);
            for (var i = 0; i < image.pixels().length; i++) {
                // Opaque: a zero alpha byte is a transparent pixel, and transparency reads as nothing.
                pixels.setAtIndex(ValueLayout.JAVA_INT, i, image.pixels()[i] | 0xFF000000);
            }

            if (!succeeded((int) CALL_CREATE_FROM_BYTE_ARRAY.invokeExact(
                    method(buffers, CRYPTOGRAPHIC_BUFFER_STATICS_CREATE_FROM_BYTE_ARRAY), buffers,
                    (int) pixels.byteSize(), pixels, out), "CreateFromByteArray")) {
                return Optional.empty();
            }
            final var buffer = out.get(ValueLayout.ADDRESS, 0);
            try {
                if (!succeeded((int) CALL_CREATE_COPY_FROM_BUFFER.invokeExact(
                        method(bitmaps, SOFTWARE_BITMAP_STATICS_CREATE_COPY_FROM_BUFFER), bitmaps,
                        buffer, BITMAP_PIXEL_FORMAT_BGRA8, image.width(), image.height(), out), "CreateCopyFromBuffer")) {
                    return Optional.empty();
                }
                final var bitmap = out.get(ValueLayout.ADDRESS, 0);
                try {
                    if (!succeeded((int) CALL_IN_OUT.invokeExact(
                            method(engine, OCR_ENGINE_RECOGNIZE_ASYNC), engine, bitmap, out), "RecognizeAsync")) {
                        return Optional.empty();
                    }
                    final var operation = out.get(ValueLayout.ADDRESS, 0);
                    try {
                        return completed(arena, operation) ? text(arena, operation) : Optional.empty();
                    } finally {
                        release(operation);
                    }
                } finally {
                    release(bitmap);
                }
            } finally {
                release(buffer);
            }
        } catch (Throwable t) {
            throw failure("recognize", t);
        }
    }

    /** Waits for the recognition, through the {@code IAsyncInfo} every asynchronous operation also is. */
    private static boolean completed(Arena arena, MemorySegment operation) throws Throwable {
        final var out = arena.allocate(ValueLayout.ADDRESS);
        if (!succeeded((int) CALL_IN_OUT.invokeExact(method(operation, QUERY_INTERFACE), operation, IID_ASYNC_INFO, out),
                "QueryInterface(IAsyncInfo)")) {
            return false;
        }
        final var info = out.get(ValueLayout.ADDRESS, 0);
        try {
            final var status = arena.allocate(ValueLayout.JAVA_INT);
            final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READ_TIMEOUT_MS);
            while (true) {
                if (!succeeded((int) CALL_OUT.invokeExact(method(info, ASYNC_INFO_GET_STATUS), info, status), "get_Status")) {
                    return false;
                }
                final var now = status.get(ValueLayout.JAVA_INT, 0);
                if (now != ASYNC_STARTED) {
                    return now == ASYNC_COMPLETED;
                }
                if (System.nanoTime() > deadline) {
                    return false;
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    // The reader gave up waiting: the operation is still released on the way out.
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        } finally {
            release(info);
        }
    }

    private static Optional<String> text(Arena arena, MemorySegment operation) throws Throwable {
        final var out = arena.allocate(ValueLayout.ADDRESS);
        if (!succeeded((int) CALL_OUT.invokeExact(method(operation, ASYNC_OPERATION_GET_RESULTS), operation, out),
                "GetResults")) {
            return Optional.empty();
        }
        final var result = out.get(ValueLayout.ADDRESS, 0);
        try {
            if (!succeeded((int) CALL_OUT.invokeExact(method(result, OCR_RESULT_GET_TEXT), result, out), "get_Text")) {
                return Optional.empty();
            }
            final var hstring = out.get(ValueLayout.ADDRESS, 0);
            try {
                return Optional.of(string(arena, hstring));
            } finally {
                final var ignored = (int) WindowsDeleteString.invokeExact(hstring);
            }
        } finally {
            release(result);
        }
    }

    // ------------------------------------------------------------------ COM plumbing

    /** A runtime class's factory, as the interface asked for — {@code NULL} when Windows has none. */
    private static MemorySegment factory(Arena arena, String className, MemorySegment iid) throws Throwable {
        final var name = hstring(arena, className);
        try {
            final var out = arena.allocate(ValueLayout.ADDRESS);
            final var got = (int) RoGetActivationFactory.invokeExact(name, iid, out);
            return succeeded(got, "RoGetActivationFactory(" + className + ")")
                    ? out.get(ValueLayout.ADDRESS, 0)
                    : MemorySegment.NULL;
        } finally {
            final var ignored = (int) WindowsDeleteString.invokeExact(name);
        }
    }

    /** The function pointer in a COM object's vtable — the object's first word points at the table. */
    private static MemorySegment method(MemorySegment object, int slot) {
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
            final var ignored = (int) CALL_THIS.invokeExact(method(object, RELEASE), object);
        } catch (Throwable t) {
            throw failure("Release", t);
        }
    }

    private static MemorySegment hstring(Arena arena, String text) throws Throwable {
        final var utf16 = arena.allocateFrom(text, StandardCharsets.UTF_16LE);
        final var out = arena.allocate(ValueLayout.ADDRESS);
        if (!succeeded((int) WindowsCreateString.invokeExact(utf16, text.length(), out), "WindowsCreateString")) {
            throw new Win32Exception("Could not make an HSTRING of " + text, null);
        }
        return out.get(ValueLayout.ADDRESS, 0);
    }

    /** An {@code HSTRING}'s characters — a null one is the empty string, as WinRT defines it. */
    private static String string(Arena arena, MemorySegment hstring) throws Throwable {
        final var length = arena.allocate(ValueLayout.JAVA_INT);
        final var chars = (MemorySegment) WindowsGetStringRawBuffer.invokeExact(hstring, length);
        final var count = length.get(ValueLayout.JAVA_INT, 0);
        if (count == 0) {
            return "";
        }
        return new String(chars.reinterpret(count * 2L).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_16LE);
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

    /** A failed {@code HRESULT} is a refusal, logged and tolerated like any failed Win32 call. */
    private static boolean succeeded(int hresult, String call) {
        if (hresult < 0) {
            log.debug("{} failed: 0x{}", call, Integer.toHexString(hresult));
            return false;
        }
        return true;
    }

    private static MethodHandle combase(String name, FunctionDescriptor descriptor) {
        final var symbol = COMBASE.find(name)
                .orElseThrow(() -> new IllegalStateException("combase.dll exports no " + name));
        return LINKER.downcallHandle(symbol, descriptor);
    }

    /** As in {@link User32}: a {@code Throwable} out of {@code invokeExact} is a bug, never an HRESULT. */
    private static Win32Exception failure(String step, Throwable cause) {
        if (cause instanceof Error error) {
            throw error;
        }
        if (cause instanceof Win32Exception already) {
            return already;
        }
        return new Win32Exception("Native call during OCR " + step + " failed", cause);
    }
}
