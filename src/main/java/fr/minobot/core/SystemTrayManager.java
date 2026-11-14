package fr.minobot.core;

import fr.minobot.ui.TrayMenuActions;
import fr.minobot.ui.TrayMenuContent;
import fr.minobot.ui.TrayMenuView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The notification-area icon — the counterpart of {@code system_tray.py}.
 *
 * <p>{@code java.awt.SystemTray} replaces {@code pystray} + {@code PIL}: the icon is rendered at the
 * size the OS asks for, rather than at a fixed 64×64 that Windows would downscale. It shows the
 * application's artwork when a bitmap is shipped — {@code /tray.png} for choice, a square mark made
 * for tiny sizes, else the full {@code /logo.png} — and falls back to a drawn golden M so the tray
 * is never empty, whatever was packaged.
 *
 * <p>Its menu is not AWT's {@code PopupMenu}, which Windows draws as its own grey list: either click on
 * the icon opens a {@link TrayMenuView}, themed like the panel.
 */
public final class SystemTrayManager implements TrayMenuActions {

    private static final Logger log = LoggerFactory.getLogger(SystemTrayManager.class);

    /**
     * The icon bitmaps, most specific first. {@code /tray.png} is a square mark drawn for 16 px;
     * {@code /logo.png} is the full portrait artwork — usable but busy once shrunk to the tray.
     */
    private static final String[] ICON_RESOURCES = {"/tray.png", "/logo.png"};

    private static final Color BROWN = new Color(0x5D4037);
    private static final Color GOLD = new Color(0xFFD700);

    private final Supplier<TrayMenuContent> content;
    private final Path folder;
    private final Runnable onQuit;
    private final TrayMenuView menu;
    private TrayIcon icon;

    /**
     * @param content    what the menu shows, read at each opening
     * @param folder     the folder {@link #openFolder} opens
     * @param menuFactory hands the menu its way back in, so neither has to be built before the other
     */
    public SystemTrayManager(Supplier<TrayMenuContent> content, Path folder, Runnable onQuit,
                             Function<TrayMenuActions, TrayMenuView> menuFactory) {
        this.content = content;
        this.folder = folder;
        this.onQuit = onQuit;
        this.menu = menuFactory.apply(this);
    }

    /**
     * Adds the icon to the notification area.
     *
     * <p>A missing tray is not fatal: the application keeps working, it just cannot be quit from
     * there.
     */
    public void start() {
        if (icon != null) {
            log.warn("System tray icon is already installed.");
            return;
        }
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            log.warn("No system tray on this session; running without an icon.");
            return;
        }

        try {
            final var tray = SystemTray.getSystemTray();
            final var size = tray.getTrayIconSize();

            final var trayIcon = new TrayIcon(buildIcon(size), "Minobot");
            trayIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent event) {
                    // A tray icon's events are in screen coordinates.
                    menu.show(content.get(), event.getX(), event.getY());
                }
            });
            tray.add(trayIcon);
            icon = trayIcon;

            log.info("System tray icon installed.");
        } catch (AWTException e) {
            log.warn("Could not install the system tray icon: {}", e.getMessage());
        }
    }

    public void stop() {
        if (icon == null) {
            return;
        }
        menu.hide();
        SystemTray.getSystemTray().remove(icon);
        icon = null;
        log.info("System tray icon removed.");
    }

    @Override
    public void openFolder() {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            log.warn("This desktop cannot open a folder: {}", folder);
            return;
        }
        // Explorer can take its time to come up, and the caller is Swing's thread.
        Thread.ofVirtual().name("tray-open-folder").start(() -> {
            try {
                Desktop.getDesktop().open(folder.toFile());
            } catch (IOException | IllegalArgumentException e) {
                log.warn("Could not open {}: {}", folder, e.getMessage());
            }
        });
    }

    @Override
    public void quit() {
        onQuit.run();
    }

    /**
     * The tray icon at the size the notification area wants: the shipped artwork when there is any,
     * else a golden M drawn to fit.
     */
    private static Image buildIcon(Dimension size) {
        final var artwork = loadArtwork();
        return artwork != null ? fit(artwork, size) : drawIcon(size);
    }

    /** The first icon bitmap on the classpath, or {@code null} when none was packaged. */
    private static BufferedImage loadArtwork() {
        for (final var resource : ICON_RESOURCES) {
            final var url = SystemTrayManager.class.getResource(resource);
            if (url == null) {
                continue;
            }
            try {
                return ImageIO.read(url);
            } catch (IOException e) {
                log.warn("Could not read the tray icon {}: {}", resource, e.getMessage());
            }
        }
        log.debug("No tray icon bitmap on the classpath: the tray falls back to its drawn M.");
        return null;
    }

    /**
     * The artwork scaled to sit inside {@code size} with its proportions kept — centred over a
     * transparent square, smoothed rather than sampled at the nearest pixel, so a large source and a
     * portrait one both come out clean at 16 px.
     */
    private static Image fit(BufferedImage artwork, Dimension size) {
        final var canvas = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
        final var graphics = canvas.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        final var scale = Math.min(size.width / (double) artwork.getWidth(),
                size.height / (double) artwork.getHeight());
        final var w = (int) Math.round(artwork.getWidth() * scale);
        final var h = (int) Math.round(artwork.getHeight() * scale);
        graphics.drawImage(artwork, (size.width - w) / 2, (size.height - h) / 2, w, h, null);

        graphics.dispose();
        return canvas;
    }

    /** A golden M on a brown square, drawn to whatever size the notification area wants. */
    private static Image drawIcon(Dimension size) {
        final var image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
        final var graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        graphics.setColor(BROWN);
        graphics.fillRect(0, 0, size.width, size.height);

        graphics.setColor(GOLD);
        graphics.setStroke(new BasicStroke(Math.max(2f, size.width / 8f)));

        final var width = size.width;
        final var height = size.height;
        final var xs = new int[]{round(0.19 * width), round(0.19 * width), round(0.50 * width), round(0.81 * width), round(0.81 * width)};
        final var ys = new int[]{round(0.81 * height), round(0.19 * height), round(0.50 * height), round(0.19 * height), round(0.81 * height)};
        graphics.drawPolyline(xs, ys, xs.length);

        graphics.dispose();
        return image;
    }

    private static int round(double value) {
        return (int) Math.round(value);
    }
}
