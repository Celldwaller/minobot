package fr.minobot.app;

import java.time.Duration;
import java.util.Locale;

/**
 * The features a player binds a key to, and the slot each one occupies in {@link Config}.
 *
 * <p>It exists so that the hotkeys can be enumerated rather than listed by hand: {@code MinobotApp}
 * walks it to register them, and the overlay walks it to show them. A feature added to the enum and
 * forgotten in the switch that gives it its action is a compile error, which is the point.
 *
 * <p>The cooldown is each hotkey's own minimum spacing: enough to swallow a double trigger, never
 * enough to feel laggy. It is dictated by the hardware and the feature, not by the player, which is
 * why it lives here and not in {@code config.json}.
 */
public enum Feature {

    MULTICLICK("Multi-window click", "multiclick_hotkey", Duration.ofMillis(10)),
    RESET_WINDOWS("Character reset", "reset_windows_hotkey", Duration.ofSeconds(1)),
    GROUP_INVITE("Group invitation", "group_invite_hotkey", Duration.ofSeconds(5)),
    WINDOW_CYCLE_NEXT("Window cycler (next)", "window_cycle_next_hotkey", Duration.ofMillis(100)),
    WINDOW_CYCLE_PREV("Window cycler (previous)", "window_cycle_prev_hotkey", Duration.ofMillis(100)),
    WINDOW_REORDER("Window reorder", "window_reorder_hotkey", Duration.ofSeconds(5)),
    OVERLAY("Overlay", "overlay_hotkey", Duration.ofMillis(200)),

    /**
     * The odd one out: its key <em>toggles</em> the turn-passer's overlay switch rather than firing an
     * action. It has a slot here so it is bound, shown in the keybinds drawer and persisted like the rest;
     * a blank hotkey only leaves it without a key, the switch still driving it. The cooldown swallows a
     * middle-click bounce without feeling laggy.
     */
    AUTO_PASS_TURN("Auto-pass turns", "auto_pass_turn_hotkey", Duration.ofMillis(300));

    private final String label;
    private final String configKey;
    private final Duration cooldown;

    Feature(String label, String configKey, Duration cooldown) {
        this.label = label;
        this.configKey = configKey;
        this.cooldown = cooldown;
    }

    /** How the feature is named in the logs, which stay in English whatever the player reads. */
    public String label() {
        return label;
    }

    /** Where the overlay finds the feature's name in the player's language — {@code feature.overlay}. */
    public String key() {
        return "feature." + name().toLowerCase(Locale.ROOT);
    }

    /**
     * The key this feature's hotkey has in {@code config.json} — the same string as the matching
     * {@code @JsonProperty} on {@link Config}. It exists so the persisted set of keybinds is
     * <em>walked</em> from this enum rather than listed by hand: see {@code OverlayState}.
     */
    public String configKey() {
        return configKey;
    }

    public Duration cooldown() {
        return cooldown;
    }

    /** The key this feature is bound to, or blank when the player has turned it off. */
    public String hotkeyIn(Config config) {
        final var hotkey = switch (this) {
            case MULTICLICK -> config.multiclickHotkey();
            case RESET_WINDOWS -> config.resetWindowsHotkey();
            case GROUP_INVITE -> config.groupInviteHotkey();
            case WINDOW_CYCLE_NEXT -> config.windowCycleNextHotkey();
            case WINDOW_CYCLE_PREV -> config.windowCyclePrevHotkey();
            case WINDOW_REORDER -> config.windowReorderHotkey();
            case OVERLAY -> config.overlayHotkey();
            case AUTO_PASS_TURN -> config.autoPassTurnHotkey();
        };
        return hotkey == null ? "" : hotkey;
    }
}
