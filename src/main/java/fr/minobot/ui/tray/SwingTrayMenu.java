package fr.minobot.ui.tray;

import fr.minobot.ui.Theme;
import fr.minobot.ui.TrayMenuActions;
import fr.minobot.ui.TrayMenuContent;
import fr.minobot.ui.TrayMenuView;
import fr.minobot.ui.components.containers.Divider;
import fr.minobot.ui.components.labels.KeyChip;
import fr.minobot.ui.utils.Draw;
import fr.minobot.ui.utils.Fonts;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;

/**
 * The tray menu, in Swing, in the panel's charcoal and ember — where the menu AWT draws is the desktop's
 * own grey list, set in the desktop's font, and reads as somebody else's program.
 *
 * <p><strong>It is the one themed surface that takes the foreground</strong>, and that is deliberate. A
 * menu nothing can dismiss is worse than no menu, and the only thing that tells a window the player
 * clicked elsewhere is losing the activation. It costs the game nothing: the player has just clicked the
 * taskbar, so no game window holds the foreground to lose it, and no keystroke sequence is under way.
 *
 * <p>It is a frame and not a {@code JWindow} because a {@code JWindow} is focusable only through a
 * visible owner, and there is none.
 */
public final class SwingTrayMenu implements TrayMenuView {

    private static final Logger log = LoggerFactory.getLogger(SwingTrayMenu.class);

    /** The mark the tray icon itself shows, so the menu is seen to come from it; the portrait logo else. */
    private static final String[] LOGO_RESOURCES = {"/tray.png", "/logo.png"};

    /** The menu's width: room for the longest item and the overlay's key chip beside its label. */
    private static final int WIDTH = 252;

    /** The logo beside the wordmark, as tall as the two lines it heads. */
    private static final int MARK = 32;

    private static final int ITEM = 30;
    private static final int DOT = 6;

    /** The room the window keeps around the sheet, so its rounded corners are not clipped. */
    private static final int MARGIN = 4;

    /**
     * A click on the tray icon while the menu is open deactivates the menu first, which hides it, and
     * then reaches the icon, which would open it again. A show this soon after a dismissal is that click.
     */
    private static final Duration REOPEN_GUARD = Duration.ofMillis(250);

    private static final BufferedImage LOGO = loadLogo();

    private final TrayMenuActions actions;

    /** Touched on the event dispatch thread only. */
    private JFrame window;
    private long dismissedAt;

    public SwingTrayMenu(TrayMenuActions actions) {
        this.actions = actions;
    }

    @Override
    public void show(TrayMenuContent content, int x, int y) {
        SwingUtilities.invokeLater(() -> {
            if (window != null && window.isVisible()) {
                hideNow();
                return;
            }
            if (System.nanoTime() - dismissedAt < REOPEN_GUARD.toNanos()) {
                return;
            }
            open(content, x, y);
        });
    }

    @Override
    public void hide() {
        SwingUtilities.invokeLater(this::hideNow);
    }

    // ------------------------------------------------------------------ the event dispatch thread

    private void open(TrayMenuContent content, int x, int y) {
        if (window == null) {
            build();
        }

        final var scale = new Scale(content.scale());
        final var sheet = new Sheet(scale);
        sheet.add(header(scale, content));
        sheet.add(Box.createVerticalStrut(scale.px(Metrics.GAP + 4)));
        sheet.add(Divider.horizontal(scale));
        sheet.add(Box.createVerticalStrut(scale.px(Metrics.GAP)));
        sheet.add(panelHint(scale, content));
        sheet.add(Box.createVerticalStrut(scale.px(Metrics.GAP)));
        sheet.add(Divider.horizontal(scale));
        sheet.add(Box.createVerticalStrut(scale.px(Metrics.GAP / 2)));
        sheet.add(new Item(scale, content.words().get("tray.open_folder"), actions::openFolder));
        sheet.add(new Item(scale, content.words().get("tray.quit"), actions::quit));

        window.setContentPane(sheet);
        final var height = sheet.getPreferredSize().height;
        window.setSize(scale.px(WIDTH) + 2 * scale.px(MARGIN), height);
        window.setLocation(placed(new Point(x, y), window.getSize()));

        window.setVisible(true);
        window.toFront();
        window.requestFocus();
    }

    private void hideNow() {
        if (window != null && window.isVisible()) {
            window.setVisible(false);
            dismissedAt = System.nanoTime();
        }
    }

    private void build() {
        window = new JFrame("Minobot");
        window.setUndecorated(true);
        // No taskbar button for a menu.
        window.setType(Window.Type.UTILITY);
        window.setAlwaysOnTop(true);

        final var device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        if (device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
            window.setBackground(new Color(0, 0, 0, 0));
        }

        window.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent event) {
                hideNow();
            }
        });
        window.getRootPane().registerKeyboardAction(event -> hideNow(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    /**
     * Where the menu goes: from the pointer towards the middle of the screen it is on, and inside that
     * screen's work area — a taskbar at the bottom opens it upwards, one on the left opens it rightwards.
     */
    private static Point placed(Point pointer, Dimension size) {
        final var area = workAreaAt(pointer);
        var left = pointer.x + size.width > area.x + area.width ? pointer.x - size.width : pointer.x;
        var top = pointer.y + size.height > area.y + area.height ? pointer.y - size.height : pointer.y;
        left = Math.clamp(left, area.x, Math.max(area.x, area.x + area.width - size.width));
        top = Math.clamp(top, area.y, Math.max(area.y, area.y + area.height - size.height));
        return new Point(left, top);
    }

    /** The screen under the pointer, less its taskbar. */
    private static Rectangle workAreaAt(Point pointer) {
        final var environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        var configuration = environment.getDefaultScreenDevice().getDefaultConfiguration();
        for (final var device : environment.getScreenDevices()) {
            if (device.getDefaultConfiguration().getBounds().contains(pointer)) {
                configuration = device.getDefaultConfiguration();
                break;
            }
        }
        final var bounds = configuration.getBounds();
        final var insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        return new Rectangle(bounds.x + insets.left, bounds.y + insets.top,
                bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom);
    }

    // ------------------------------------------------------------------ the rows

    /** The logo, the wordmark, and under it how many characters Minobot sees in game. */
    private static JComponent header(Scale scale, TrayMenuContent content) {
        final var row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(new EmptyBorder(0, scale.px(Metrics.GAP), 0, scale.px(Metrics.GAP)));

        if (LOGO != null) {
            row.add(new Mark(scale));
            row.add(Box.createHorizontalStrut(scale.px(Metrics.GAP + 2)));
        }

        final var lines = new JPanel();
        lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
        lines.setOpaque(false);

        final var wordmark = new JLabel("MINOBOT");
        wordmark.setForeground(Theme.TEXT);
        wordmark.setFont(scale.tracked(scale.font(Fonts.CONDENSED, Metrics.WORDMARK), Metrics.HEADING_TRACKING));
        lines.add(wordmark);
        lines.add(Box.createVerticalStrut(scale.px(2)));
        lines.add(status(scale, content));

        row.add(lines);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    /** A green dot while a character is in game, the quietest grey while none is. */
    private static JComponent status(Scale scale, TrayMenuContent content) {
        final var words = content.words();
        final var playing = content.playing();
        final var text = switch (playing) {
            case 0 -> words.get("tray.playing.none");
            case 1 -> words.get("tray.playing.one");
            default -> words.get("tray.playing.many", playing);
        };
        final var color = playing > 0 ? Theme.CONNECTED : Theme.GHOST;

        final var label = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics graphics) {
                final var canvas = Draw.smooth(graphics);
                Draw.dot(canvas, 0, getHeight(), scale.px(DOT), color);
                canvas.dispose();
                super.paintComponent(graphics);
            }
        };
        label.setForeground(Theme.MUTED);
        label.setFont(scale.font(Fonts.MEDIUM, Metrics.SMALL));
        label.setBorder(new EmptyBorder(0, scale.px(DOT + Metrics.GAP - 2), 0, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    /** The key that opens the panel, since the menu cannot: the panel opens over a game window. */
    private static JComponent panelHint(Scale scale, TrayMenuContent content) {
        final var row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(new EmptyBorder(0, scale.px(Metrics.GAP), 0, scale.px(Metrics.GAP)));

        final var label = new JLabel(content.words().get("tray.panel"));
        label.setForeground(Theme.FAINT);
        label.setFont(scale.font(Fonts.MEDIUM, Metrics.LABEL));

        final var chip = KeyChip.of(scale, content.words(), content.overlayHotkey());
        chip.setMaximumSize(chip.getPreferredSize());

        row.add(label);
        row.add(Box.createHorizontalGlue());
        row.add(chip);
        return row;
    }

    // ------------------------------------------------------------------ the pieces

    /** The sheet every row rests on: the panel's darkest surface, edged, with the panel's corners. */
    private static final class Sheet extends JPanel {

        private final Scale scale;

        private Sheet(Scale scale) {
            this.scale = scale;
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            final var inset = scale.px(MARGIN) + scale.px(Metrics.GAP + 2);
            setBorder(new EmptyBorder(scale.px(MARGIN) + scale.px(Metrics.GUTTER), inset,
                    scale.px(MARGIN) + scale.px(Metrics.GAP), inset));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            final var canvas = Draw.smooth(graphics);
            final var margin = scale.px(MARGIN);
            final var radius = scale.px(Metrics.RADIUS);
            final var width = getWidth() - 2 * margin;
            final var height = getHeight() - 2 * margin;
            canvas.setColor(Theme.BACKGROUND);
            canvas.fillRoundRect(margin, margin, width, height, radius, radius);
            canvas.setColor(Theme.EDGE_STRONG);
            canvas.drawRoundRect(margin, margin, width - 1, height - 1, radius, radius);
            canvas.dispose();
        }
    }

    /** The logo, fitted to its square with its proportions kept. */
    private static final class Mark extends JComponent {

        private Mark(Scale scale) {
            final var side = new Dimension(scale.px(MARK), scale.px(MARK));
            setPreferredSize(side);
            setMaximumSize(side);
            setMinimumSize(side);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            final var fit = Math.min(getWidth() / (double) LOGO.getWidth(),
                    getHeight() / (double) LOGO.getHeight());
            final var width = (int) Math.round(LOGO.getWidth() * fit);
            final var height = (int) Math.round(LOGO.getHeight() * fit);
            final var canvas = Draw.smooth(graphics);
            canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            canvas.drawImage(LOGO, (getWidth() - width) / 2, (getHeight() - height) / 2, width, height, null);
            canvas.dispose();
        }
    }

    /** One thing to do: a line of text that lights up under the pointer and acts on release. */
    private final class Item extends JComponent {

        private final Scale scale;
        private final String text;
        private boolean hovered;

        private Item(Scale scale, String text, Runnable action) {
            this.scale = scale;
            this.text = text;
            setFont(scale.font(Fonts.MEDIUM, Metrics.BODY));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            final var size = new Dimension(Integer.MAX_VALUE, scale.px(ITEM));
            setMaximumSize(size);
            setPreferredSize(new Dimension(scale.px(WIDTH), scale.px(ITEM)));

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hovered = false;
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    if (contains(event.getPoint())) {
                        hideNow();
                        action.run();
                    }
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            final var canvas = Draw.smooth(graphics);
            if (hovered) {
                final var radius = scale.px(Metrics.RADIUS_TILE);
                canvas.setColor(Theme.HOVER);
                canvas.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            }
            canvas.setFont(getFont());
            canvas.setColor(hovered ? Theme.TEXT : Theme.TEXT_SOFT);
            final var metrics = canvas.getFontMetrics();
            final var baseline = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            final var pad = scale.px(Metrics.GAP);
            canvas.drawString(Draw.elide(metrics, text, getWidth() - 2 * pad), pad, baseline);
            canvas.dispose();
        }
    }

    /** The logo, or {@code null} if none was shipped: the wordmark then stands alone. */
    private static BufferedImage loadLogo() {
        for (final var name : LOGO_RESOURCES) {
            final var resource = SwingTrayMenu.class.getResource(name);
            if (resource == null) {
                continue;
            }
            try {
                return ImageIO.read(resource);
            } catch (IOException e) {
                log.warn("Could not read the tray menu logo {}: {}", name, e.getMessage());
            }
        }
        return null;
    }
}
