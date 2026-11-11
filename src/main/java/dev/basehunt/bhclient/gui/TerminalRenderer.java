package dev.basehunt.bhclient.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.List;
import java.util.function.Function;

/**
 * A text-box renderer that feeds the terminal's command registry into Meteor's built-in completion popup.
 *
 * <p>Meteor's text box already shows a completion list and inserts the highlighted entry on Tab; the only
 * thing missing is the list itself, which a renderer supplies through {@link #getCompletions}. Routing the
 * registry's {@code complete} through here means the terminal gets real Tab completion - command names,
 * subcommands and argument candidates - without a bespoke popup.
 *
 * <p>The renderer is instantiated reflectively by the text box, so it must keep a no-argument constructor;
 * the completion source is supplied through a static field set just before the box is created.
 */
public final class TerminalRenderer implements WTextBox.Renderer {
    /** Where the current terminal's completion candidates come from. */
    static volatile Function<String, List<String>> completionSource = line -> List.of();

    @Override
    public void render(GuiRenderer renderer, double x, double y, String text, Color color) {
        renderer.text(text, x, y, color, true);
    }

    @Override
    public List<String> getCompletions(String text, int cursor) {
        List<String> candidates = completionSource.apply(text);
        return candidates == null ? List.of() : candidates;
    }
}
