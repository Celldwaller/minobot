package fr.minobot.feature.group;

import fr.minobot.core.ActivityLog;
import fr.minobot.core.FocusManager;
import fr.minobot.core.Requests;
import fr.minobot.core.WindowManager;
import fr.minobot.core.domain.GameWindow;
import fr.minobot.core.domain.Request;
import fr.minobot.core.input.Input;
import fr.minobot.i18n.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.KeyEvent;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Chain-invites every character into one group — the counterpart of {@code group_manager.py}.
 *
 * <p>A relay: the first character invites the second, the second accepts and invites the third, and
 * so on. Each step needs the previous one to have actually landed in the game, which is where
 * {@link Requests} comes in: the invited character is asked — a box on their screen — and the
 * sequence waits for that rather than guessing a delay.
 *
 * <p>The relay types into the game, so it holds the foreground from end to end
 * ({@link FocusManager#takeOver()}). It has to: the invitation it waits for at every step is the same
 * one the request auto-focus reacts to, and that focus, left free, arrives in the middle of a
 * {@code /invite} and sends the rest of it to another character's window.
 */
public final class GroupManager {

    private static final Logger log = LoggerFactory.getLogger(GroupManager.class);

    /**
     * How long the game is given to show the invitation. The screen watcher reads every window at every
     * look, so the box is found within a look of being drawn.
     */
    private static final Duration CONFIRMATION_TIMEOUT = Duration.ofSeconds(5);

    /** Time the chat line takes to appear, before the command can be pasted into it. */
    private static final int CHAT_OPEN_MILLIS = 25;

    /**
     * The one shape a line sent to the chat may have here. Nothing else must ever be typed and
     * confirmed: a clipboard the paste failed to overwrite has, in the past, sent the player's own
     * clipboard to {@code /say}. Every command is checked against this before its ENTER goes out.
     */
    private static final Pattern INVITE_COMMAND = Pattern.compile("/invite \\S.*");

    private final WindowManager windows;
    private final Input input;
    private final FocusManager focus;
    private final ActivityLog activity;
    private final Requests requests;

    private final AtomicBoolean running = new AtomicBoolean();

    /** The invitation the relay is waiting on, or {@code null} outside a step. */
    private final AtomicReference<Confirmation> awaited = new AtomicReference<>();

    public GroupManager(WindowManager windows, Input input, FocusManager focus,
                        Requests requests, ActivityLog activity) {
        this.windows = windows;
        this.input = input;
        this.focus = focus;
        this.activity = activity;
        this.requests = requests;

        requests.onRequest(this::onRequest);
    }

    /** Invites every character into the first one's group, then hands the focus back to it. */
    public void inviteAll() {
        if (!running.compareAndSet(false, true)) {
            log.warn("The group invitation sequence is already running.");
            return;
        }

        try (final var _ = focus.takeOver()) {
            windows.refresh();
            final var characters = windows.orderedWindows();
            if (characters.size() < 2) {
                log.warn("Only {} character(s) found; at least 2 are needed to form a group.",
                        characters.size());
                return;
            }

            final var leader = characters.getFirst();
            log.info("Inviting {} characters into the group of '{}'.", characters.size(), leader.name());

            relay(characters);

            log.info("Returning the focus to '{}'.", leader.name());
            focus.focus(leader.hwnd());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            log.warn("The group invitation sequence was interrupted.");
        } catch (RuntimeException e) {
            log.error("Error during the group invitation sequence.", e);
        } finally {
            awaited.set(null);
            running.set(false);
        }
    }

    /** Each character invites the next one, who accepts and becomes the inviter of the one after. */
    private void relay(List<GameWindow> characters) throws InterruptedException {
        for (var step = 0; step < characters.size() - 1; step++) {
            final var invitee = characters.get(step + 1);

            if (!inviteAndAccept(characters.get(step), invitee)) {
                log.error("The relay stops at '{}'; the characters after it stay out of the group.",
                        invitee.name());
                // Worth a line of its own: the group is half-formed, and the player is looking at a
                // window that gives no sign of it. The step it stopped at is where they must look.
                activity.record(Text.of("activity.relay_stopped"), Text.of("activity.at", invitee.name()));
                return;
            }
        }

        log.info("Every character is in the group.");
        activity.record(Text.of("activity.relay_completed"), Text.of("activity.characters", characters.size()));
    }

    /** One link of the relay: the inviter sends the command, the invitee accepts what it gets. */
    private boolean inviteAndAccept(GameWindow inviter, GameWindow invitee) throws InterruptedException {
        log.info("'{}' is inviting '{}'...", inviter.name(), invitee.name());

        // Armed before the command goes out: the invitation can be seen before we reach the wait below.
        final var confirmation = expectInvitationFor(invitee.name());

        return sendInvite(inviter, invitee.name())
                && confirmed(confirmation)
                && accept(invitee);
    }

    /** Types {@code /invite Name} in the inviter's chat. */
    private boolean sendInvite(GameWindow inviter, String invitee) throws InterruptedException {
        if (!focus.focus(inviter.hwnd())) {
            log.error("Could not focus '{}': its invitation would have been typed elsewhere.", inviter.name());
            return false;
        }

        final var command = "/invite " + invitee;
        if (!INVITE_COMMAND.matcher(command).matches()) {
            log.error("Refusing to send '{}': it is not a well-formed invite command.", command);
            return false;
        }

        input.pressKey(KeyEvent.VK_ENTER); // opens the chat
        Thread.sleep(CHAT_OPEN_MILLIS);

        // The command is pasted through the clipboard. A false here means it never landed there
        // intact — another application raced us for the clipboard — so nothing was pasted. Sending
        // ENTER now would fire whatever is on the chat line, often the player's own clipboard, into
        // /say; stop the relay instead, and close the empty chat line we opened.
        if (!input.pasteString(command)) {
            log.error("'{}' was not placed on the clipboard intact; stopping the relay rather than "
                    + "risk sending clipboard content to the chat.", command);
            input.pressKey(KeyEvent.VK_ESCAPE);
            return false;
        }

        input.pressKey(KeyEvent.VK_ENTER); // sends it

        return true;
    }

    /** Waits for the game to show the invited character the invitation. */
    private boolean confirmed(Confirmation confirmation) throws InterruptedException {
        if (confirmation.arrivedWithin(CONFIRMATION_TIMEOUT)) {
            return true;
        }

        // Accepting anyway would press ENTER in a window with no invitation on screen, where it does
        // not accept anything: it opens the chat, and the next step's command is typed into the game.
        log.warn("No invitation seen for '{}' within {}s; the game may not have received the command, "
                        + "or its window may be minimized.",
                confirmation.character(), CONFIRMATION_TIMEOUT.toSeconds());
        return false;
    }

    /** Presses ENTER on the invitation the game is showing the invited character. */
    private boolean accept(GameWindow invitee) {
        if (!focus.focus(invitee.hwnd())) {
            log.error("Could not focus '{}': its invitation stays on screen, unaccepted.", invitee.name());
            return false;
        }

        input.pressKey(KeyEvent.VK_ENTER);
        log.debug("'{}' joined the group.", invitee.name());
        return true;
    }

    private Confirmation expectInvitationFor(String character) {
        final var confirmation = new Confirmation(character, new CountDownLatch(1));
        awaited.set(confirmation);
        return confirmation;
    }

    /** Runs on a request's virtual thread, and releases the step waiting in {@link #inviteAll()}. */
    void onRequest(GameWindow receiver, Request request) {
        // One read: the awaited name and its latch must come from the same step, or an invitation seen
        // between two steps could release the latch of the next one, which would then not wait at all.
        final var confirmation = awaited.get();
        if (confirmation == null || !confirmation.matches(receiver, request)) {
            return;
        }

        log.info("Invitation seen for '{}'.", confirmation.character());
        confirmation.arrived();
    }

    /** The invitation that tells one step of the relay it has landed: the invited character, and the wait. */
    private record Confirmation(String character, CountDownLatch received) {

        boolean matches(GameWindow receiver, Request request) {
            return request.kind() == Request.Kind.GROUP_INVITE && receiver.name().equalsIgnoreCase(character);
        }

        void arrived() {
            received.countDown();
        }

        boolean arrivedWithin(Duration timeout) throws InterruptedException {
            return received.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
