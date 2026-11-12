package fr.minobot.ui;

/** What a click on the spell marks asks for: the one button beside their hint. */
public interface MarksActions {

    /** The button beside the hint was pressed. Called on the event dispatch thread: hand the work off. */
    void act();
}
