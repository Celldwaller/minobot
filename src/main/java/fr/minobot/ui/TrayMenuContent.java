package fr.minobot.ui;

import fr.minobot.i18n.Language;
import fr.minobot.i18n.Words;

/**
 * What the tray menu shows: how many characters Minobot sees in game, and the key that opens the panel.
 *
 * <p>The hotkey is there because the tray is the one place a player looks for Minobot <em>outside</em> the
 * game, and the panel opens only over a game window: the menu cannot open it, so it says how to.
 *
 * <p>The scale is the player's {@code overlay_scale}, so the menu reads at the size the panel does on the
 * same monitor.
 */
public record TrayMenuContent(double scale, Language language, int playing, String overlayHotkey) {

    public Words words() {
        return Words.in(language);
    }
}
