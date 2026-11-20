package fr.minobot;

import fr.minobot.app.MinobotApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

/** Entry point: enforces a single running instance, then hands over to {@link MinobotApp}. */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static final int LOCK_PORT = 12345;

    static void main() {
        // The packaged application has no console: an error that ends a thread is written nowhere else.
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
                log.error("Thread '{}' died of an uncaught error.", thread.getName(), error));

        try (final var lock = acquireInstanceLock()) {
            if (lock == null) {
                log.error("Another Minobot instance already holds port {}. Exiting.", LOCK_PORT);
                return;
            }

            final var app = new MinobotApp();
            Runtime.getRuntime().addShutdownHook(new Thread(app::stop, "shutdown"));

            app.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Interrupted, shutting down.");
        } catch (IOException e) {
            log.error("Could not release the instance lock: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Fatal error during startup.", e);
        }

        System.exit(0);
    }

    private static ServerSocket acquireInstanceLock() {
        try {
            return new ServerSocket(LOCK_PORT, 1, InetAddress.getLoopbackAddress());
        } catch (IOException _) {
            return null;
        }
    }
}
