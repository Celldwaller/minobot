package fr.minobot.ui.overlay;

import fr.minobot.app.Config;
import fr.minobot.core.domain.DofusClass;
import fr.minobot.i18n.Words;
import fr.minobot.ui.OverlayActions;
import fr.minobot.ui.OverlayContent;
import fr.minobot.ui.OverlayView;
import fr.minobot.ui.Theme;
import fr.minobot.ui.components.buttons.CloseCross;
import fr.minobot.ui.components.buttons.PrimaryButton;
import fr.minobot.ui.components.buttons.SecondaryButton;
import fr.minobot.ui.components.containers.Card;
import fr.minobot.ui.overlay.characters.TeamCard;
import fr.minobot.ui.overlay.characters.clazz.ClassIcons;
import fr.minobot.ui.overlay.characters.clazz.ClassPicker;
import fr.minobot.ui.overlay.console.ConsoleCard;
import fr.minobot.ui.overlay.keybinds.KeybindsDrawer;
import fr.minobot.ui.utils.Metrics;
import fr.minobot.ui.utils.Scale;
import fr.minobot.win32.Rect;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;

/**
 * The panel, in Swing — the orchestrator that owns the window and lays the sections out on it. The
 * drawing of each part lives in its own class ({@link HeaderBar}, {@link TeamCard}, {@link ConsoleCard},
 * {@link KeybindsDrawer}, {@link ClassPicker}, {@link SizeSlider}); this wires them onto the
 * {@link Sheet} and keeps the panel's transient state — whether the keybinds drawer is open, and which
 * character the class picker is open for.
 *
 * <p><strong>The panel is two halves and one line.</strong> The header names the surface and shows the
 * key that summons it; the {@code TeamCard} on the left is what the player <em>edits</em>; the
 * {@code ConsoleCard} on the right is what Minobot has been <em>doing</em>. The team is given a fixed
 * width and the console takes the rest, so a long character name never moves the console.
 *
 * <p><strong>It must never take the foreground.</strong> There is one screen, and
 * {@code core.FocusManager} is what keeps the features from fighting over it: a panel that stole the
 * focus would land between two keystrokes of the invitation relay and send the rest of an
 * {@code /invite} to another character's window. Hence {@code setFocusableWindowState(false)} — a
 * window that receives the mouse and never the activation — and hence, too, the fact that
 * <strong>nothing here is typed</strong>: a keybind is read from the keyboard by
 * {@link OverlayActions#captureHotkey()}, not typed into a text field that would need the focus.
 *
 * <p>That one line is enough, and it was <em>measured</em> against the real game rather than assumed —
 * both when the panel is shown and when it is clicked, the character the player is on keeps the
 * screen. <strong>Do not add {@code WS_EX_NOACTIVATE} to the window to make sure</strong>: Swing
 * already does this one, and the extended style would be a native call bought for nothing.
 *
 * <p><strong>Every size is a natural size, and none of them is a pixel.</strong> The panel covers the
 * game, and the game is played on screens of every size; each size reaches the screen multiplied by the
 * player's scale through a {@link Scale} — {@link Scale#px} for a length, {@link Scale#font} for a
 * typeface. A size that skips them is a size that will be wrong on somebody's monitor.
 *
 * <p>Two threads meet here, and neither may do the other's work. Swing lives on the event dispatch
 * thread, while {@code toggle()} arrives on the virtual thread of a hotkey — so every method below
 * hands its work to the EDT. And {@code captureHotkey()} <em>blocks</em>, waiting for a human, so it is
 * the one thing that must leave the EDT — see {@link KeybindsDrawer}.
 */
public final class SwingOverlay implements OverlayView {

    /** The close cross in the header: the mouse's way out, where the hotkey is the keyboard's. */
    private static final int CLOSE_SIZE = 28;

    /** Room for eight characters; beyond that the list scrolls rather than the sheet growing. */
    private static final int CHARACTER_LIST_HEIGHT = 264;

    private final OverlayActions actions;

    /** Touched on the event dispatch thread only. Built on first show: there may never be one. */
    private JWindow window;

    // The sections, each drawing one part of the panel. Built once, on first show; asked for a fresh
    // component at each lay, at the scale of the moment.
    private HeaderBar header;
    private TeamCard team;
    private ConsoleCard console;
    private KeybindsDrawer keybindsDrawer;
    private ClassPicker classPicker;
    private SizeSlider sizeSlider;

    private JPanel sheet;
    private JPanel keybinds;
    private JComponent classPickerComp;

    /**
     * The character the class picker is open for, or {@code null} when it is closed. The picker is a
     * modal over the whole sheet, so it is the panel's state, not the player's: nothing on disk remembers
     * a half-made choice.
     */
    private String classPickerFor;

    /** What every size on the sheet was computed with. A change to it is a sheet built again. */
    private Scale scale;

    /**
     * Whether the keybinds are unfolded. The panel's, not the player's: it is a drawer the view opens,
     * and there is nothing in {@code config.json} for it to be remembered in.
     */
    private boolean keybindsOpen;

    /** So the panel can be redrawn after a capture that came to nothing, or a drawer that opened. */
    private OverlayContent lastContent;
    private Rect lastBounds;

    /** Read from the hotkey's thread, so it cannot live inside Swing. */
    private volatile boolean visible;

    public SwingOverlay(OverlayActions actions) {
        this.actions = actions;
    }

    @Override
    public void show(OverlayContent content, Rect bounds) {
        visible = true;
        SwingUtilities.invokeLater(() -> draw(content, bounds));
    }

    /**
     * Called many times a second while the player drags their window, so it does no more than it must:
     * the panel is already built, and only its bounds have changed.
     */
    @Override
    public void moveTo(Rect bounds) {
        SwingUtilities.invokeLater(() -> {
            if (window == null || !window.isVisible()) {
                return;
            }
            lastBounds = bounds;
            window.setBounds(bounds.left(), bounds.top(), bounds.width(), bounds.height());
        });
    }

    @Override
    public void hide() {
        visible = false;
        SwingUtilities.invokeLater(() -> {
            if (window != null) {
                window.setVisible(false);
            }
        });
    }

    /**
     * Whether the player has the panel up.
     *
     * <p>Answered from the flag rather than from Swing: the hotkey asks on its own thread, and it asks
     * the instant it is pressed — before the EDT has had a chance to draw anything.
     */
    @Override
    public boolean isVisible() {
        return visible;
    }

    // ------------------------------------------------------------------ the event dispatch thread

    private void draw(OverlayContent content, Rect bounds) {
        lastContent = content;
        lastBounds = bounds;

        if (window == null) {
            build();
        }

        // Where the player had the character list scrolled to. Everything below rebuilds the sheet, so
        // the list comes back a new component with a new scroll bar at zero — and a drag that reorders
        // two rows at the foot of a long list would be answered by throwing the player back to the top
        // of it, which reads as the panel undoing what they just did.
        final var scrolled = team.scrollOffset();

        // The sheet is built again rather than patched: every size on it was computed with the scale of
        // the moment — the paddings, the fonts, the height of a row — and walking them all to correct
        // them would be the walk that builds them.
        lay(content, content.scale(), bounds);

        final var fits = scaleThatFits(bounds);
        if (fits < scale.factor()) {
            lay(content, fits, bounds);
        }

        // The panel <em>is</em> the game's client area: exactly as wide and as tall, and starting where
        // the game itself starts — below the title bar, whose buttons must stay clickable.
        window.setBounds(bounds.left(), bounds.top(), bounds.width(), bounds.height());
        window.setVisible(true);
        window.getContentPane().revalidate();
        window.getContentPane().repaint();

        // Behind the layout that revalidate() has just queued, never in front of it: a scroll bar whose
        // extent is not settled clamps any value to zero, and the offset would be quietly lost.
        SwingUtilities.invokeLater(() -> team.scrollTo(scrolled));
    }

    /** The sheet, at one scale: built, filled, and shrunk to the room the game leaves it. */
    private void lay(OverlayContent content, double factor, Rect bounds) {
        this.scale = new Scale(factor);

        sheet = sheet(content);
        keybinds = keybindsDrawer.build(scale, content);
        keybinds.setVisible(keybindsOpen);
        classPickerComp = classPickerFor == null ? null : classPicker.build(scale, classPickerFor, content);
        window.setContentPane(new Sheet());

        team.fill(content);

        shrinkListToFit(bounds);
    }

    private void build() {
        window = new JWindow();
        window.setAlwaysOnTop(true);

        // The whole point: the panel receives the mouse, and never the activation.
        window.setFocusableWindowState(false);

        translucentIfSupported();

        final var classIcons = new ClassIcons();
        header = new HeaderBar(actions);
        team = new TeamCard(actions, classIcons, this::openClassPicker);
        console = new ConsoleCard(actions);
        keybindsDrawer = new KeybindsDrawer(actions, this::redraw, this::closeKeybinds);
        classPicker = new ClassPicker(actions, classIcons, this::dismissClassPicker, this::chooseClass);
        sizeSlider = new SizeSlider(actions);
    }

    /**
     * A translucent background needs the desktop to support per-pixel alpha. It normally does — but a
     * remote session or a disabled compositor does not, and there the panel is simply opaque rather
     * than absent.
     */
    private void translucentIfSupported() {
        final var device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        if (device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
            window.setBackground(new Color(0, 0, 0, 0));
        }
    }

    // ------------------------------------------------------------------ the sheet

    /** The panel proper: the header, the team beside the console, and the size at its foot. */
    private JPanel sheet(OverlayContent content) {
        final var padding = px(Metrics.PADDING);

        final var card = Card.sheet(scale, Theme.BACKGROUND).pinnedTo(sheetWidth());
        card.setBorder(new EmptyBorder(padding, padding, padding, padding));

        card.add(header.build(scale, content, CloseCross.button(scale, this::hide, px(CLOSE_SIZE))));
        card.add(Box.createVerticalStrut(px(Metrics.PADDING)));
        card.add(body(content));
        card.add(Box.createVerticalStrut(px(Metrics.PADDING)));
        card.add(sizeSlider.build(scale, content.words()));
        return card;
    }

    /** The two halves, side by side: the team the player edits, the console it acts through. */
    private JComponent body(OverlayContent content) {
        final var row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        row.add(team.build(scale, content));
        row.add(Box.createHorizontalStrut(px(Metrics.GUTTER)));
        row.add(console.build(scale, content, keybindsButton(content.words())));
        return row;
    }

    /** The drawer's switch. It says which way it will go, which is all a chevron is for. */
    private JButton keybindsButton(Words words) {
        final var title = words.get("keybinds.title");
        final JButton button = keybindsOpen
                ? new PrimaryButton(scale, title + " ‹")
                : new SecondaryButton(scale, title + " ›");
        button.addActionListener(_ -> {
            keybindsOpen = !keybindsOpen;
            redraw();
        });
        return button;
    }

    /** The drawer's own close cross lands here — the same flip its switch does, from the other side. */
    private void closeKeybinds() {
        keybindsOpen = false;
        redraw();
    }

    // ------------------------------------------------------------------ the class picker's open state

    /** Opens the class picker for a character — the picker's open/closed state is the panel's, not disk's. */
    private void openClassPicker(String character) {
        classPickerFor = character;
        redraw();
    }

    private void dismissClassPicker() {
        classPickerFor = null;
        redraw();
    }

    /**
     * Pins a class and closes the picker. The picker is cleared first, so the redraw the assignment
     * triggers finds it already shut; the assignment persists on its own thread through the controller.
     */
    private void chooseClass(String character, DofusClass clazz) {
        classPickerFor = null;
        actions.assignClass(character, clazz);
    }

    // ------------------------------------------------------------------ where the sheet sits

    /**
     * The dim over the game, with the sheet resting on it.
     *
     * <p>The window covers the whole client area, so the game is dimmed rather than hidden — the player
     * has to see the character they are configuring. The sheet is placed <strong>in the middle of it</strong>:
     * it is where the player is already looking, and a panel that opens under the eye is a panel that
     * needs no hunting for. The keybinds drawer hangs off its right edge.
     *
     * <p>The sheet gives up the middle for one reason only: a drawer that would otherwise open past the
     * right edge of the game. It then slides left by exactly what the drawer is short of, and no more.
     * A sheet that stayed centred there would have to be <em>shrunk</em> for the drawer to fit beside it,
     * and a panel that changes size when a button is clicked reads as a bug; a panel that shifts by an
     * inch reads as a drawer opening.
     *
     * <p>No layout manager, because the rule <em>is</em> a layout manager, and it is shorter than the
     * constraints one would have to be given.
     */
    private final class Sheet extends JPanel {

        private Sheet() {
            setLayout(null);
            setOpaque(false);
            // Swing paints index 0 last, so the first added sits on top. The picker, while open, must
            // cover everything and be the only thing to click — so it goes in first, ahead of the sheet
            // and the drawer beside it.
            if (classPickerComp != null) {
                add(classPickerComp);
            }
            add(sheet);
            add(keybinds);
        }

        @Override
        public void doLayout() {
            final var main = sheet.getPreferredSize();
            final var drawer = keybinds.getPreferredSize();

            final var top = (getHeight() - main.height) / 2;
            var left = (getWidth() - main.width) / 2;

            if (keybinds.isVisible()) {
                final var past = left + main.width + px(Metrics.GUTTER) + drawer.width
                        - (getWidth() - px(Metrics.PADDING));
                left = Math.max(px(Metrics.PADDING), left - Math.max(0, past));
            }

            sheet.setBounds(left, top, main.width, main.height);
            keybinds.setBounds(left + main.width + px(Metrics.GUTTER), top, drawer.width, drawer.height);

            // The picker covers the whole sheet: it dims what is behind it and catches every click.
            if (classPickerComp != null) {
                classPickerComp.setBounds(0, 0, getWidth(), getHeight());
            }
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            final var canvas = (Graphics2D) graphics.create();
            canvas.setColor(Theme.BACKDROP);
            canvas.fillRect(0, 0, getWidth(), getHeight());
            canvas.dispose();

            super.paintComponent(graphics);
        }
    }

    /**
     * The list of characters gives way first when the sheet is taller than the game.
     *
     * <p>It is the one part of the panel that can be short and still be usable, because it scrolls — and
     * it stands above the size slider, which must not be pushed off the bottom of the screen.
     */
    private void shrinkListToFit(Rect bounds) {
        final var full = px(CHARACTER_LIST_HEIGHT);
        listHeight(full);

        final var overflow = tallest() - room(bounds).height;
        if (overflow > 0) {
            listHeight(Math.max(team.rowHeight(), full - overflow));
        }
    }

    private void listHeight(int height) {
        team.resizeTo(height);
        sheet.revalidate();
    }

    /**
     * The scale the game's window can actually hold — the player's, or less.
     *
     * <p>The sheet is scaled and the window is not: it is the game's, and the game may be a small
     * window on a large screen. Past a certain size the sheet no longer fits in it, and what falls off
     * the edge is the slider — <strong>the one control that could bring it back</strong>. So the panel
     * never grows past the game it covers, and the slider reads what the player is actually looking at.
     */
    private double scaleThatFits(Rect bounds) {
        final var room = room(bounds);
        final var fits = Math.min(
                room.height / (double) tallest(),
                room.width / (double) widest());

        if (fits >= 1) {
            return scale.factor();
        }
        return Math.max(Config.MIN_OVERLAY_SCALE, scale.factor() * fits);
    }

    /** How tall the panel stands: the sheet, or the drawer beside it if that one is taller. */
    private int tallest() {
        return Math.max(sheet.getPreferredSize().height,
                keybinds.isVisible() ? keybinds.getPreferredSize().height : 0);
    }

    /**
     * How wide it stands: the sheet, and the drawer alongside it when that one is open. The sheet slides
     * off-centre to make room rather than the two overlapping, so their widths simply add up.
     */
    private int widest() {
        final var drawer = keybinds.isVisible()
                ? px(Metrics.GUTTER) + keybinds.getPreferredSize().width
                : 0;
        return sheet.getPreferredSize().width + drawer;
    }

    /** What the game leaves the sheet, once the dim has kept its margin around it. */
    private Dimension room(Rect bounds) {
        return new Dimension(bounds.width() - 2 * px(Metrics.PADDING),
                bounds.height() - 2 * px(Metrics.PADDING));
    }

    private void redraw() {
        if (lastContent != null && lastBounds != null) {
            draw(lastContent, lastBounds);
        }
    }

    /**
     * How wide the sheet stands: the team's fixed width, the console beside it, the gutter between them
     * and the padding around them. Computed from its parts rather than written down — a literal here
     * would be a number to keep in step with three others, and it would lose.
     *
     * <p><strong>Its parts are added up after scaling, never before.</strong> {@link Scale#px} rounds, so
     * {@code px(a + b)} is not always {@code px(a) + px(b)}: a sheet pinned to the scaled total comes out
     * a pixel short of the cards laid out on it, and a card pinned to a width cannot give that pixel
     * back — so the one at the right edge is pushed past the sheet and clipped there.
     */
    private int sheetWidth() {
        return px(TeamCard.WIDTH) + px(Metrics.GUTTER) + ConsoleCard.width(scale)
                + 2 * px(Metrics.PADDING);
    }

    /** A natural length, at the size the player asked for — the orchestrator's own shorthand for its layout. */
    private int px(int natural) {
        return scale.px(natural);
    }
}
