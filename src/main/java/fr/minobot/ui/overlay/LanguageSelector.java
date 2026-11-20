package fr.minobot.ui.overlay;

import fr.minobot.i18n.Language;
import fr.minobot.ui.Theme;
import fr.minobot.ui.utils.Draw;
import fr.minobot.ui.utils.Fonts;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The language selector in the header: a button showing the language being read — its flag and its code —
 * and, once clicked, a menu of every language under its flag and its own name.
 *
 * <p><strong>The menu is not a {@code JPopupMenu}</strong>: a popup is a window of its own, and it takes
 * the activation the panel must never take. It is a layer laid over the sheet instead, as the class picker
 * is — clear everywhere but the menu, and catching every click: a click on a language picks it, a click
 * anywhere else only closes the menu.
 *
 * <p>Whether the menu is open is the panel's state, not this class's: the orchestrator holds it, and asks
 * for the button and the menu afresh at each lay, as it does every section.
 */
public final class LanguageSelector {

    private static final Logger log = LoggerFactory.getLogger(LanguageSelector.class);

    /** A flag's natural size: three by two, the proportion of most flags, and of the French one. */
    private static final int FLAG_WIDTH = 18;
    private static final int FLAG_HEIGHT = 12;

    /** The room the button and a menu row keep around their content. */
    private static final int PAD_X = 8;

    /** The side of the chevron on the button and of the check beside the chosen language. */
    private static final int GLYPH = 8;

    /** The menu's own margin, so a hovered row's fill does not touch its edge. */
    private static final int INSET = 4;

    /** The drop between the button and the menu hanging under it. */
    private static final int DROP = 4;

    /** The flags as shipped, or none for a language whose flag is missing: it is then listed by name only. */
    private final Map<Language, BufferedImage> flags = loadFlags();

    /** Each flag smoothed down once per size it is drawn at, rather than at every paint. */
    private final Map<String, BufferedImage> drawn = new HashMap<>();

    /**
     * @param open     whether the menu hangs under it, which turns its chevron over
     * @param onToggle what a click on it does: opens the menu, or closes it
     */
    public JComponent button(Scale scale, Language current, boolean open, Runnable onToggle) {
        return new Button(scale, current, open, onToggle);
    }

    /**
     * The menu, as a layer over the whole sheet, hung under {@code anchor} and right-aligned with it.
     *
     * @param anchor   the button the menu hangs from, as built at this same lay
     * @param onChoose handed the language clicked, the current one included
     * @param onDismiss a click anywhere but on the menu
     */
    public JComponent menu(Scale scale, Language current, JComponent anchor,
                           Consumer<Language> onChoose, Runnable onDismiss) {
        return new Layer(scale, current, anchor, onChoose, onDismiss);
    }

    // ------------------------------------------------------------------ the button

    private final class Button extends JComponent {

        private final Scale scale;
        private final Language current;
        private final boolean open;
        private boolean hovered;

        private Button(Scale scale, Language current, boolean open, Runnable onToggle) {
            this.scale = scale;
            this.current = current;
            this.open = open;
            setFont(scale.font(Fonts.SEMIBOLD, Metrics.SMALL));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            final var size = new Dimension(
                    scale.px(PAD_X) + flagWidth(scale, current) + getFontMetrics(getFont()).stringWidth(current.name())
                            + scale.px(Metrics.GAP) + scale.px(GLYPH) + scale.px(PAD_X),
                    scale.px(Metrics.ROW));
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(size);

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
                    onToggle.run();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            final var canvas = Draw.smooth(graphics);
            final var radius = scale.px(Metrics.RADIUS_TILE);
            canvas.setColor(hovered || open ? Theme.HOVER : Theme.RAISED);
            canvas.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            canvas.setColor(open ? Theme.ACCENT_EDGE : Theme.EDGE);
            canvas.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);

            var x = scale.px(PAD_X);
            x += paintFlag(canvas, scale, current, x, getHeight());

            canvas.setFont(getFont());
            canvas.setColor(hovered || open ? Theme.TEXT : Theme.TEXT_SOFT);
            final var fm = canvas.getFontMetrics();
            canvas.drawString(current.name(), x, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            x += fm.stringWidth(current.name()) + scale.px(Metrics.GAP);

            chevron(canvas, x, (getHeight() - scale.px(GLYPH)) / 2, scale.px(GLYPH), open,
                    scale.px(2));
            canvas.dispose();
        }
    }

    // ------------------------------------------------------------------ the menu

    /** Clear over the whole sheet, so a click beside the menu closes it rather than reaching what is under. */
    private final class Layer extends JPanel {

        private final Scale scale;
        private final JComponent anchor;
        private final JComponent list;

        private Layer(Scale scale, Language current, JComponent anchor,
                      Consumer<Language> onChoose, Runnable onDismiss) {
            super(null);
            this.scale = scale;
            this.anchor = anchor;
            setOpaque(false);

            list = new Entries(scale, current, onChoose);
            add(list);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent event) {
                    onDismiss.run();
                }
            });
        }

        /**
         * Under the button, its right edge on the button's, so the menu opens toward the middle of the
         * panel and never past the sheet's corner. The button is found where it is now: the orchestrator lays
         * the sheet out before this layer, so its bounds are settled.
         */
        @Override
        public void doLayout() {
            final var size = list.getPreferredSize();
            final var button = SwingUtilities.convertRectangle(anchor.getParent(), anchor.getBounds(), this);
            list.setBounds(button.x + button.width - size.width, button.y + button.height + scale.px(DROP),
                    size.width, size.height);
        }
    }

    /** The languages, one row each; the one being read carries the check. */
    private final class Entries extends JPanel {

        private final Scale scale;

        private Entries(Scale scale, Language current, Consumer<Language> onChoose) {
            super(null);
            this.scale = scale;
            setOpaque(false);

            final var font = scale.font(Fonts.MEDIUM, Metrics.LABEL);
            final var fm = getFontMetrics(font);
            var widest = 0;
            for (final var language : Language.values()) {
                widest = Math.max(widest, flagWidth(scale, language) + fm.stringWidth(language.nativeName()));
            }

            final var inset = scale.px(INSET);
            final var rowWidth = scale.px(PAD_X) + widest + scale.px(Metrics.GUTTER) + scale.px(GLYPH)
                    + scale.px(PAD_X);
            final var rowHeight = scale.px(Metrics.ROW);

            var y = inset;
            for (final var language : Language.values()) {
                final var row = new Row(scale, language, language == current, font, onChoose);
                row.setBounds(inset, y, rowWidth, rowHeight);
                add(row);
                y += rowHeight;
            }
            setPreferredSize(new Dimension(rowWidth + 2 * inset, y + inset));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            final var canvas = Draw.smooth(graphics);
            final var radius = scale.px(Metrics.RADIUS_TILE);
            canvas.setColor(Theme.SURFACE);
            canvas.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            canvas.setColor(Theme.EDGE_STRONG);
            canvas.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            canvas.dispose();
        }
    }

    private final class Row extends JComponent {

        private final Scale scale;
        private final Language language;
        private final boolean chosen;
        private boolean hovered;

        private Row(Scale scale, Language language, boolean chosen, Font font,
                    Consumer<Language> onChoose) {
            this.scale = scale;
            this.language = language;
            this.chosen = chosen;
            setFont(font);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

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
                        onChoose.accept(language);
                    }
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            final var canvas = Draw.smooth(graphics);
            if (hovered) {
                final var radius = scale.px(Metrics.RADIUS_CHIP);
                canvas.setColor(Theme.HOVER);
                canvas.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            }

            var x = scale.px(PAD_X);
            x += paintFlag(canvas, scale, language, x, getHeight());

            canvas.setFont(getFont());
            canvas.setColor(chosen || hovered ? Theme.TEXT : Theme.TEXT_SOFT);
            final var fm = canvas.getFontMetrics();
            canvas.drawString(language.nativeName(), x, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);

            if (chosen) {
                final var side = scale.px(GLYPH);
                check(canvas, getWidth() - scale.px(PAD_X) - side, (getHeight() - side) / 2, side,
                        scale.px(2));
            }
            canvas.dispose();
        }
    }

    // ------------------------------------------------------------------ the drawing

    /** The room a flag takes with the gap after it, or none for a language with no flag shipped. */
    private int flagWidth(Scale scale, Language language) {
        return flags.containsKey(language) ? scale.px(FLAG_WIDTH) + scale.px(Metrics.GAP - 2) : 0;
    }

    /** Draws the flag centred on a line of {@code height}, and says how far along it took the line. */
    private int paintFlag(Graphics2D canvas, Scale scale, Language language, int x, int height) {
        final var flag = flags.get(language);
        if (flag == null) {
            return 0;
        }
        final var width = scale.px(FLAG_WIDTH);
        final var tall = scale.px(FLAG_HEIGHT);
        final var image = drawn.computeIfAbsent(language + "@" + width + "x" + tall,
                _ -> rounded(flag, width, tall, scale.px(2)));
        canvas.drawImage(image, x, (height - tall) / 2, null);
        return flagWidth(scale, language);
    }

    /**
     * The flag cropped to three by two from its middle — the Union Jack is wider, and squeezing it would
     * show — smoothed down to its size, with its corners rounded as the panel's chips are.
     */
    private static BufferedImage rounded(BufferedImage flag, int width, int height, int radius) {
        final var target = width / (double) height;
        var cropWidth = flag.getWidth();
        var cropHeight = flag.getHeight();
        if (cropWidth / (double) cropHeight > target) {
            cropWidth = (int) Math.round(cropHeight * target);
        } else {
            cropHeight = (int) Math.round(cropWidth / target);
        }
        final var cropped = flag.getSubimage((flag.getWidth() - cropWidth) / 2,
                (flag.getHeight() - cropHeight) / 2, cropWidth, cropHeight);

        final var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final var canvas = image.createGraphics();
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        canvas.setColor(Color.WHITE);
        canvas.fillRoundRect(0, 0, width, height, radius * 2, radius * 2);
        canvas.setComposite(AlphaComposite.SrcIn);
        canvas.drawImage(cropped.getScaledInstance(width, height, Image.SCALE_SMOOTH), 0, 0, null);
        canvas.dispose();
        return image;
    }

    /** A chevron pointing down, or up while the menu it opens is open. */
    private static void chevron(Graphics2D canvas, int x, int y, int side, boolean up, int stroke) {
        final var top = y + side / 4;
        final var bottom = y + side - side / 4;
        canvas.setColor(Theme.FAINT);
        canvas.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (up) {
            canvas.drawPolyline(new int[]{x, x + side / 2, x + side}, new int[]{bottom, top, bottom}, 3);
        } else {
            canvas.drawPolyline(new int[]{x, x + side / 2, x + side}, new int[]{top, bottom, top}, 3);
        }
    }

    /** The check beside the language being read: in the ember, since it is the one choice that holds. */
    private static void check(Graphics2D canvas, int x, int y, int side, int stroke) {
        canvas.setColor(Theme.ACCENT_HOVER);
        canvas.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        canvas.drawPolyline(new int[]{x, x + side * 2 / 5, x + side},
                new int[]{y + side / 2, y + side - side / 8, y + side / 8}, 3);
    }

    /** Read once, off the classpath like the logo; a flag that is missing or unreadable is simply left out. */
    private static Map<Language, BufferedImage> loadFlags() {
        final var flags = new EnumMap<Language, BufferedImage>(Language.class);
        for (final var language : Language.values()) {
            final var path = language.flagResource();
            final var resource = LanguageSelector.class.getResource(path);
            if (resource == null) {
                log.debug("No {}: {} is listed by name only.", path, language);
                continue;
            }
            try {
                final var flag = ImageIO.read(resource);
                if (flag != null) {
                    flags.put(language, flag);
                } else {
                    log.warn("Could not decode the flag {}.", path);
                }
            } catch (IOException e) {
                log.warn("Could not read the flag {}: {}", path, e.getMessage());
            }
        }
        return flags;
    }
}
