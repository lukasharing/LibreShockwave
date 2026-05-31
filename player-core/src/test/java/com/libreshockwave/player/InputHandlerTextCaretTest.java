package com.libreshockwave.player;

import com.libreshockwave.cast.MemberType;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.player.event.EventDispatcher;
import com.libreshockwave.player.input.InputState;
import com.libreshockwave.player.render.output.SimpleTextRenderer;
import com.libreshockwave.player.render.output.TextRenderer;
import com.libreshockwave.player.render.pipeline.StageRenderer;
import com.libreshockwave.player.sprite.SpriteState;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputHandlerTextCaretTest {

    @Test
    void caretAtEndUsesTextBoundaryAfterLastCharacter() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = editableTextMember("lukasharin", 167);
        InputState inputState = new InputState();
        SpriteState sprite = focusedSprite(inputState, 7, 12, 51, 167, member);
        InputHandler handler = handler(inputState, sprite, member);

        inputState.setSelStart(member.getTextContent().length());
        inputState.setSelEnd(member.getTextContent().length());

        TextRenderer renderer = member.getTextRenderer();
        int[] previousOffByOne = renderer.charPosToLoc(member.getTextContent(), member.getTextContent().length(),
                member.getTextFont(), member.getTextFontSize(), member.getTextFontStyle(),
                member.getTextFixedLineSpace(), member.getTextAlignment(), sprite.getWidth());
        int[] expectedAfterEnd = renderer.charPosToLoc(member.getTextContent(), member.getTextContent().length() + 1,
                member.getTextFont(), member.getTextFontSize(), member.getTextFontStyle(),
                member.getTextFixedLineSpace(), member.getTextAlignment(), sprite.getWidth());

        int[] caret = handler.getCaretInfo();

        assertTrue(expectedAfterEnd[0] > previousOffByOne[0],
                "sanity check: end-boundary caret must be right of the last glyph");
        assertEquals(sprite.getLocH() + expectedAfterEnd[0], caret[0],
                "focused input caret should draw after the final character, not over it");
    }

    @Test
    void textareaCaretAtEndUsesLastLineEndBoundary() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = editableTextMember("A\nB", 120);
        member.setProp("lineheight", Datum.of(18));
        InputState inputState = new InputState();
        SpriteState sprite = focusedSprite(inputState, 8, 20, 30, 120, member);
        InputHandler handler = handler(inputState, sprite, member);

        inputState.setSelStart(member.getTextContent().length());
        inputState.setSelEnd(member.getTextContent().length());

        TextRenderer renderer = member.getTextRenderer();
        int[] expectedAfterEnd = renderer.charPosToLoc(member.getTextContent(), member.getTextContent().length() + 1,
                member.getTextFont(), member.getTextFontSize(), member.getTextFontStyle(),
                member.getTextFixedLineSpace(), member.getTextAlignment(), sprite.getWidth());
        int[] caretBounds = renderer.getCaretBounds(member.getTextFont(), member.getTextFontSize(),
                member.getTextFontStyle(), member.getTextFixedLineSpace());

        int[] caret = handler.getCaretInfo();

        assertEquals(sprite.getLocH() + expectedAfterEnd[0], caret[0]);
        assertEquals(sprite.getLocV() + expectedAfterEnd[1] + caretBounds[0], caret[1],
                "textarea caret should stay on the final line after multiline text");
    }

    @Test
    void overflowingCenteredInputKeepsEndCaretInsideVisibleField() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        String text = "codexfake@example.comFakePass123";
        CastMember member = editableTextMember(text, 120);
        InputState inputState = new InputState();
        SpriteState sprite = focusedSprite(inputState, 9, 40, 51, 120, member);
        InputHandler handler = handler(inputState, sprite, member);

        inputState.setSelStart(text.length());
        inputState.setSelEnd(text.length());

        TextRenderer renderer = member.getTextRenderer();
        int[] unscrolledEnd = renderer.charPosToLoc(text, text.length() + 1,
                member.getTextFont(), member.getTextFontSize(), member.getTextFontStyle(),
                member.getTextFixedLineSpace(), "left", sprite.getWidth());

        int[] caret = handler.getCaretInfo();
        int rightEdge = sprite.getLocH() + sprite.getWidth();

        assertTrue(unscrolledEnd[0] > sprite.getWidth(),
                "sanity check: fixture text must overflow the field");
        assertTrue(caret[0] >= rightEdge - 2,
                "overflowing editable input should scroll to the text tail");
        assertTrue(caret[0] <= rightEdge + 2,
                "overflowing editable input caret should not disappear past the right edge");
    }

    @Test
    void overflowingCenteredTextareaKeepsLastLineCaretInsideVisibleField() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        String text = "A\ncodexfake@example.comFakePass123";
        CastMember member = editableTextMember(text, 120);
        member.setProp("lineheight", Datum.of(18));
        InputState inputState = new InputState();
        SpriteState sprite = focusedSprite(inputState, 10, 40, 51, 120, member);
        InputHandler handler = handler(inputState, sprite, member);

        inputState.setSelStart(text.length());
        inputState.setSelEnd(text.length());

        int[] caret = handler.getCaretInfo();
        int rightEdge = sprite.getLocH() + sprite.getWidth();

        assertTrue(caret[0] >= rightEdge - 2,
                "overflowing editable textarea should scroll the active line to the text tail");
        assertTrue(caret[0] <= rightEdge + 2,
                "overflowing editable textarea caret should not disappear past the right edge");
    }

    @Test
    void editableCaretHeightMatchesMemberFontSize() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = editableTextMember("abc", 120);
        InputState inputState = new InputState();
        SpriteState sprite = focusedSprite(inputState, 11, 40, 51, 120, member);
        InputHandler handler = handler(inputState, sprite, member);

        inputState.setSelStart(1);
        inputState.setSelEnd(1);

        int[] caret = handler.getCaretInfo();

        assertEquals(member.getTextFontSize(), caret[2],
                "focused editable caret height should match the text member font size");
    }

    @Test
    void spriteEditablePropertyMakesTextMemberAcceptKeyboardFocus() {
        CastMember.setTextRenderer(new SimpleTextRenderer());
        CastMember member = editableTextMember("abc", 120);
        member.setProp("editable", Datum.FALSE);
        InputState inputState = new InputState();
        SpriteState sprite = focusedSprite(inputState, 12, 40, 51, 120, member);
        sprite.setEditable(true);
        InputHandler handler = handler(inputState, sprite, member);

        inputState.setSelStart(1);
        inputState.setSelEnd(1);

        int[] caret = handler.getCaretInfo();

        assertTrue(caret != null && caret.length == 3,
                "sprite(n).editable should make an otherwise non-editable text member focusable");
    }

    private static CastMember editableTextMember(String text, int width) {
        CastMember member = new CastMember(1, 10001, MemberType.TEXT);
        member.setProp("font", Datum.of("V"));
        member.setProp("fontsize", Datum.of(18));
        member.setProp("lineheight", Datum.of(21));
        member.setProp("alignment", Datum.symbol("center"));
        member.setProp("rect", new Datum.Rect(0, 0, width, 21));
        member.setProp("editable", Datum.of(1));
        member.setProp("text", Datum.of(text));
        return member;
    }

    private static SpriteState focusedSprite(InputState inputState, int channel,
                                             int locH, int locV, int width,
                                             CastMember member) {
        inputState.setKeyboardFocusSprite(channel);
        SpriteState sprite = new SpriteState(channel);
        sprite.setLocH(locH);
        sprite.setLocV(locV);
        sprite.setWidth(width);
        sprite.setHeight(42);
        sprite.setDynamicMember(member.getCastLibNumber(), member.getMemberNumber());
        return sprite;
    }

    private static InputHandler handler(InputState inputState, SpriteState sprite, CastMember member) {
        StageRenderer stageRenderer = new StageRenderer(null);
        stageRenderer.getSpriteRegistry().getAll().put(sprite.getChannel(), sprite);
        CastLibManager castLibManager = new CastLibManager(null, null) {
            @Override
            public CastMember getDynamicMember(int castLibNumber, int memberNumber) {
                return member;
            }
        };
        return new InputHandler(inputState, stageRenderer, castLibManager,
                () -> 0, () -> (EventDispatcher) null);
    }
}
