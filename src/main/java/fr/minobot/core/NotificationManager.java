package fr.minobot.core;

import fr.minobot.core.domain.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Polls the Windows notification database and dispatches the game's toasts — the counterpart of
 * {@code notification_manager.py}.
 *
 * <p>Windows stores every toast in a SQLite file; reading it is how the application learns that a
 * character was whispered — the one game event not read off the screen. The read is read-only, on a database Windows keeps in
 * WAL mode, which works (proven by the phase 0 spike).
 */
public final class NotificationManager {

    private static final Logger log = LoggerFactory.getLogger(NotificationManager.class);

    private static final String QUERY = "SELECT Id, Payload FROM Notification ORDER BY Id DESC LIMIT ?";

    /** {@code SQLITE_OPEN_READONLY} — sqlite-jdbc's way of opening without claiming a write lock. */
    private static final String READ_ONLY = "1";

    /** A whisper card a quarter of a second late is still at once, to a player reading the chat. */
    private static final long POLL_MILLIS = 250;

    /** Windows purges the table as toasts are dismissed; it never holds anywhere near this many. */
    private static final int BATCH_SIZE = 10;

    private final Path databasePath;
    private final List<Consumer<Notification>> callbacks = new CopyOnWriteArrayList<>();
    private final ExecutorService dispatcher = Executors.newVirtualThreadPerTaskExecutor();

    private final AtomicReference<Thread> thread = new AtomicReference<>();

    private volatile boolean listening;
    private long lastId;

    public NotificationManager() {
        this(defaultDatabasePath());
    }

    public NotificationManager(Path databasePath) {
        this.databasePath = databasePath;
    }

    public static Path defaultDatabasePath() {
        final var localAppData = System.getenv("LOCALAPPDATA");
        return Path.of(localAppData, "Microsoft", "Windows", "Notifications", "wpndatabase.db");
    }

    /** Callbacks run on their own virtual thread, so a slow one cannot hold up the polling. */
    public void register(Consumer<Notification> callback) {
        callbacks.add(callback);
    }

    /** Starts polling on a background thread and returns immediately. */
    public void start() {
        if (listening) {
            log.warn("Notification manager is already running.");
            return;
        }
        if (!Files.exists(databasePath)) {
            log.error("Notification database not found at {}", databasePath);
            return;
        }

        listening = true;
        thread.set(Thread.ofVirtual().name("notification-manager").start(this::listen));
        log.info("Starting notification manager on {}", databasePath);
    }

    public void stop() {
        if (!listening) {
            return;
        }
        listening = false;

        final var current = thread.getAndSet(null);
        if (current != null) {
            current.interrupt();
        }
        dispatcher.shutdownNow();
        log.info("Notification manager stopped.");
    }

    private void listen() {
        try (final var connection = connect()) {
            log.info("Connected to the Windows notification database.");
            primeLastId(connection);
            poll(connection);
        } catch (SQLException e) {
            log.error("Cannot open the notification database: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            log.info("Notification database connection closed.");
        }
    }

    private Connection connect() throws SQLException {
        final var url = "jdbc:sqlite:" + databasePath;

        final var readOnly = new Properties();
        readOnly.setProperty("open_mode", READ_ONLY);
        try {
            return DriverManager.getConnection(url, readOnly);
        } catch (SQLException e) {
            log.warn("Read-only connection failed ({}), falling back to read-write.", e.getMessage());
            return DriverManager.getConnection(url);
        }
    }

    /**
     * Adopts the newest notification without dispatching it.
     *
     * <p>The database holds the toasts of the last few minutes. Starting from {@code lastId = 0}
     * replays them all on launch — the application would raise a card for
     * every whisper of the last few minutes, long since read.
     */
    private void primeLastId(Connection connection) {
        for (final var toast : readBatch(connection)) {
            lastId = Math.max(lastId, toast.id());
        }
        log.debug("Ignoring the notifications already in the database (up to Id {}).", lastId);
    }

    private void poll(Connection connection) throws InterruptedException {
        while (listening) {
            try {
                for (final var toast : readBatch(connection)) {
                    if (toast.id() > lastId) {
                        lastId = toast.id();
                        dispatch(toast.payload());
                    }
                }
            } catch (RuntimeException e) {
                log.error("Unexpected error in the notification polling loop.", e);
                Thread.sleep(POLL_MILLIS);
            }

            Thread.sleep(POLL_MILLIS);
        }
    }

    /**
     * The most recent notifications, oldest first.
     *
     * <p>An empty result is normal, not a failure: Windows purges the table when the toasts are
     * dismissed, so it sits empty most of the time.
     */
    private List<Toast> readBatch(Connection connection) {
        final var batch = new ArrayList<Toast>();

        try (final var statement = connection.prepareStatement(QUERY)) {
            statement.setInt(1, BATCH_SIZE);

            try (final var rows = statement.executeQuery()) {
                while (rows.next()) {
                    batch.add(new Toast(rows.getLong("Id"), payloadOf(rows)));
                }
            }
        } catch (SQLException e) {
            log.error("Database error while reading the notifications: {}", e.getMessage());
            return List.of();
        }

        // The query walks the ids backwards to get the newest ones; dispatch in chronological order.
        return batch.reversed();
    }

    private static String payloadOf(ResultSet rows) throws SQLException {
        final var raw = rows.getBytes("Payload");
        return raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
    }

    private void dispatch(String payload) {
        final var notification = parseToast(payload);
        if (notification.isEmpty()) {
            return;
        }

        log.debug("Notification: '{}' - '{}'", notification.get().title(), notification.get().message());
        for (final var callback : callbacks) {
            dispatcher.execute(() -> {
                try {
                    callback.accept(notification.get());
                } catch (Exception e) {
                    log.error("Error in a notification callback.", e);
                }
            });
        }
    }

    /**
     * Extracts the toast's texts: the first is the title, the second the message.
     *
     * <p>Not every payload is a toast — Windows stores tiles and badges in the same table — and a
     * non-toast is simply not ours.
     */
    static Optional<Notification> parseToast(String payload) {
        if (payload == null || !payload.contains("<toast")) {
            return Optional.empty();
        }

        try {
            final var document = documentBuilderFactory()
                    .newDocumentBuilder()
                    .parse(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));

            final var elements = document.getElementsByTagName("text");
            final var texts = new ArrayList<String>();
            for (var i = 0; i < elements.getLength(); i++) {
                final var text = elements.item(i).getTextContent();
                if (text != null && !text.isBlank()) {
                    texts.add(text.strip());
                }
            }

            if (texts.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Notification(texts.get(0), texts.size() > 1 ? texts.get(1) : ""));
        } catch (Exception e) {
            log.debug("Ignoring an unparseable notification payload: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** The payload comes from outside the application: no external entities, no DTD. */
    private static DocumentBuilderFactory documentBuilderFactory() throws ParserConfigurationException {
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private record Toast(long id, String payload) {
    }
}
