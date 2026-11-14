package fr.minobot.ui;

/**
 * The menu the notification-area icon opens — another door to the outside world beside
 * {@link OverlayView}, {@link ToastView} and {@link BannerView}, and kept just as narrow.
 *
 * <p>Only its implementation knows about Swing. It draws what it is handed and asks for what the player
 * wants through {@link TrayMenuActions}; it never reaches for the configuration.
 */
public interface TrayMenuView {

    /**
     * Opens the menu at the pointer, or closes it if the same click is what just dismissed it.
     *
     * @param x the pointer in screen coordinates, as the tray icon reported it — the view turns the menu
     *          back inside the screen it is on, so a click on a taskbar at any edge opens it whole
     */
    void show(TrayMenuContent content, int x, int y);

    void hide();
}
