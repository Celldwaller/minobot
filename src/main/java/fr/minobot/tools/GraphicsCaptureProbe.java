package fr.minobot.tools;

import fr.minobot.app.Config;
import fr.minobot.app.Settings;
import fr.minobot.core.WindowManager;
import fr.minobot.core.vision.RequestDialog;
import fr.minobot.core.vision.TurnBanner;
import fr.minobot.win32.Capture;
import fr.minobot.win32.Frame;
import fr.minobot.win32.User32;
import fr.minobot.win32.WindowsOcr;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A developer's probe, not a feature: reads every game window both ways for a while — the compositor's
 * frame ({@link User32#frame}, what the watcher reads) and the window painted whole ({@link User32#capture})
 * — and says what each costs, and whether the vision reads the same thing off both.
 *
 * <pre>
 * java --enable-native-access=ALL-UNNAMED -cp "target/minobot.jar;target/lib/*" fr.minobot.tools.GraphicsCaptureProbe [seconds] [intervalMs]
 * </pre>
 *
 * <p>The first and last frames of each window, read whole, land in {@code captures/frames-<timestamp>/}.
 */
public final class GraphicsCaptureProbe {

    static void main(String[] args) throws IOException, InterruptedException {
        final var seconds = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        final var intervalMs = args.length > 1 ? Long.parseLong(args[1]) : 100L;

        final var ocr = WindowsOcr.start().orElseThrow(() -> new IllegalStateException("No Windows OCR language for this user."));
        final var vision = new Vision(new TurnBanner(ocr), new RequestDialog(ocr));

        final var api = User32.instance();
        final var windows = new WindowManager(api, new Settings(Config.defaults()));
        windows.refresh();
        if (windows.windows().isEmpty()) {
            System.out.println("No game window on this desktop.");
            return;
        }
        final var folder = Path.of("captures", "frames-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Files.createDirectories(folder);

        final var probes = windows.windows().stream().map(window -> new Probe(window.name(), window.hwnd())).toList();
        final var looks = (int) (seconds * 1000 / intervalMs);
        for (var look = 1; look <= looks; look++) {
            final var started = System.nanoTime();
            for (final var probe : probes) {
                probe.look(api, vision, look == 1 || look == looks ? folder : null);
            }
            final var left = intervalMs - (System.nanoTime() - started) / 1_000_000;
            if (left > 0) {
                Thread.sleep(left);
            }
        }

        probes.forEach(Probe::report);
        System.out.println("Frames in " + folder.toAbsolutePath());
        ocr.close();
    }

    private static final class Probe {

        private final String name;
        private final long hwnd;

        private final Stat frame = new Stat();
        private final Stat frameReading = new Stat();
        private final Stat capture = new Stat();
        private final Stat captureReading = new Stat();
        private int looks;
        private int noFrame;
        private int agreements;
        private int disagreements;

        Probe(String name, long hwnd) {
            this.name = name;
            this.hwnd = hwnd;
        }

        void look(User32 api, Vision vision, Path saveIn) throws IOException {
            looks++;
            var start = System.nanoTime();
            final var seen = api.frame(hwnd);
            frame.add(System.nanoTime() - start);
            if (seen.isEmpty()) {
                noFrame++;
                return;
            }
            start = System.nanoTime();
            final var seenInFrame = vision.describe(seen.get());
            frameReading.add(System.nanoTime() - start);
            if (saveIn != null) {
                final var whole = seen.get().region(0, 0, seen.get().width(), seen.get().height());
                if (whole.isPresent()) {
                    ImageIO.write(image(whole.get()), "png", saveIn.resolve(fileName(name) + "-" + looks + ".png").toFile());
                }
            }

            start = System.nanoTime();
            final var painted = api.capture(hwnd);
            capture.add(System.nanoTime() - start);
            if (painted.isEmpty()) {
                return;
            }
            start = System.nanoTime();
            final var seenPainted = vision.describe(painted.get());
            captureReading.add(System.nanoTime() - start);

            if (seenInFrame.equals(seenPainted)) {
                agreements++;
            } else {
                disagreements++;
                System.out.printf("%-20s look %d: the frame shows \"%s\", the painted window \"%s\"%n",
                        name, looks, seenInFrame, seenPainted);
            }
            if (!seenInFrame.equals("nothing")) {
                System.out.printf("%-20s look %d: %s%n", name, looks, seenInFrame);
            }
        }

        void report() {
            System.out.printf("%s — %d looks, %d with no frame%n", name, looks, noFrame);
            System.out.printf("  frame          %s%n", frame);
            System.out.printf("  read off it    %s%n", frameReading);
            System.out.printf("  PrintWindow    %s%n", capture);
            System.out.printf("  read off it    %s%n", captureReading);
            System.out.printf("  the vision agrees on %d looks, differs on %d%n", agreements, disagreements);
        }
    }

    /** Everything Minobot reads off a game window, said in one line. */
    private record Vision(TurnBanner banner, RequestDialog dialog) {

        String describe(Frame frame) {
            final var fighter = banner.fighter(frame).map(found -> "banner: " + found);
            final var request = dialog.request(frame).map(found -> "request: " + found);
            if (fighter.isEmpty() && request.isEmpty()) {
                return "nothing";
            }
            return fighter.orElse("") + (fighter.isPresent() && request.isPresent() ? ", " : "") + request.orElse("");
        }
    }

    /** Milliseconds, summed over the looks. */
    private static final class Stat {

        private long count;
        private long total;
        private long worst;

        void add(long nanos) {
            count++;
            total += nanos;
            worst = Math.max(worst, nanos);
        }

        @Override
        public String toString() {
            return count == 0 ? "-" : String.format(Locale.ROOT, "%6.2f ms on average, %6.2f at worst",
                    total / 1e6 / count, worst / 1e6);
        }
    }

    private static BufferedImage image(Capture capture) {
        final var image = new BufferedImage(capture.width(), capture.height(), BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, capture.width(), capture.height(), capture.pixels(), 0, capture.width());
        return image;
    }

    /** A character name is the player's, and may carry what a file name cannot. */
    private static String fileName(String name) {
        return name.replaceAll("[^\\p{L}\\p{N}_-]", "_");
    }

    private GraphicsCaptureProbe() {
    }
}
