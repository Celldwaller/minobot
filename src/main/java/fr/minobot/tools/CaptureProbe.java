package fr.minobot.tools;

import fr.minobot.app.Config;
import fr.minobot.app.Settings;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.Cast;
import fr.minobot.core.vision.ChatLog;
import fr.minobot.core.vision.Hud;
import fr.minobot.core.vision.RequestDialog;
import fr.minobot.core.vision.SpellBar;
import fr.minobot.core.vision.TurnBanner;
import fr.minobot.win32.Capture;
import fr.minobot.win32.User32;
import fr.minobot.win32.WindowsOcr;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * A developer's probe, not a feature: captures every game window to PNG files, so what Minobot can
 * see of a background character — and where on it a given piece of the game's interface sits — can
 * be looked at before any feature is built on it. Each shot is also read by {@link TurnBanner},
 * {@link RequestDialog}, {@link ChatLog} — the casts in its last lines — and {@link SpellBar}.
 *
 * <pre>
 * java --enable-native-access=ALL-UNNAMED -cp target/minobot.jar fr.minobot.tools.CaptureProbe [shots] [intervalMs]
 * java --enable-native-access=ALL-UNNAMED -cp target/minobot.jar fr.minobot.tools.CaptureProbe --read captures/&lt;timestamp&gt;
 * </pre>
 *
 * <p>Several shots a second apart cover a whole fight turn with nothing touched. The files land in
 * {@code captures/<timestamp>/}, named after the character and the shot; {@code --read} reads such a
 * folder again, with no game running, once the reading has changed.
 */
public final class CaptureProbe {

    private static final Path ROOT = Path.of("captures");

    static void main(String[] args) throws IOException, InterruptedException {
        final var ocr = WindowsOcr.start().orElseThrow(() -> new IllegalStateException("No Windows OCR language for this user."));
        final var vision = new Vision(new TurnBanner(ocr), new RequestDialog(ocr), new ChatLog(ocr));
        if (args.length == 2 && args[0].equals("--read")) {
            reread(vision, Path.of(args[1]));
            return;
        }
        final var shots = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        final var intervalMs = args.length > 1 ? Long.parseLong(args[1]) : 1000L;

        final var api = User32.instance();
        final var windows = new WindowManager(api, new Settings(Config.defaults()));
        windows.refresh();
        if (windows.windows().isEmpty()) {
            System.out.println("No game window on this desktop.");
            return;
        }

        final var folder = ROOT.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Files.createDirectories(folder);

        for (var shot = 1; shot <= shots; shot++) {
            for (final var window : windows.windows()) {
                final var started = System.nanoTime();
                final var capture = api.capture(window.hwnd());
                final var tookMs = (System.nanoTime() - started) / 1_000_000;

                if (capture.isEmpty()) {
                    System.out.printf("%-20s shot %d: no capture (minimized or refused)%n", window.name(), shot);
                    continue;
                }
                final var file = folder.resolve("%s-%03d.png".formatted(fileName(window.name()), shot));
                ImageIO.write(image(capture.get()), "png", file.toFile());
                System.out.printf("%-20s shot %d: %dx%d in %d ms, %s%s -> %s%n", window.name(), shot,
                        capture.get().width(), capture.get().height(), tookMs,
                        vision.describe(capture.get()),
                        capture.get().isUniform() ? " (UNIFORM: nothing was really painted)" : "", file);
            }
            if (shot < shots) {
                Thread.sleep(intervalMs);
            }
        }
    }

    private static void reread(Vision vision, Path folder) throws IOException {
        try (final var files = Files.list(folder)) {
            for (final var file : files.filter(f -> f.toString().endsWith(".png")).sorted().toList()) {
                System.out.printf("%-30s %s%n", file.getFileName(), vision.describe(capture(ImageIO.read(file.toFile()))));
            }
        }
    }

    /** Everything Minobot reads off a game window, said in one line. */
    private record Vision(TurnBanner banner, RequestDialog dialog, ChatLog chat) {

        String describe(Capture capture) {
            return banner.fighter(capture).map(name -> "banner: " + name).orElse("no banner")
                    + dialog.request(capture).map(request -> ", request: " + request).orElse("")
                    + ", " + Hud.of(capture)
                    + (SpellBar.isUp(capture) ? ", spell bar" : ", no spell bar")
                    + chat.lines(capture).map(Vision::casts).orElse(", no chat");
        }

        private static String casts(List<String> lines) {
            final var casts = lines.stream().flatMap(line -> Cast.in(line).stream())
                    .map(cast -> cast.fighter() + (cast.weapon() ? " swings " : " casts ") + cast.action())
                    .toList();
            return casts.isEmpty() ? ", no cast in chat" : ", chat: " + String.join(" / ", casts);
        }
    }

    private static BufferedImage image(Capture capture) {
        final var image = new BufferedImage(capture.width(), capture.height(), BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, capture.width(), capture.height(), capture.pixels(), 0, capture.width());
        return image;
    }

    private static Capture capture(BufferedImage image) {
        final var pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        for (var i = 0; i < pixels.length; i++) {
            pixels[i] &= 0xFFFFFF;
        }
        return new Capture(image.getWidth(), image.getHeight(), pixels);
    }

    /** A character name is the player's, and may carry what a file name cannot. */
    private static String fileName(String name) {
        return name.replaceAll("[^\\p{L}\\p{N}_-]", "_");
    }

    private CaptureProbe() {
    }
}
