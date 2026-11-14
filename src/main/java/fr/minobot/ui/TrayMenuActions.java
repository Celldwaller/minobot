package fr.minobot.ui;

/**
 * What the player can ask for through the tray menu. Both calls run on Swing's thread and neither blocks.
 */
public interface TrayMenuActions {

    /** Opens the folder Minobot lives in — its {@code config.json}, its {@code overlay.json}, its logs. */
    void openFolder();

    void quit();
}
