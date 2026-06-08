package com.libreshockwave.player;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.id.InkMode;
import com.libreshockwave.player.behavior.BehaviorManager;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.event.EventDispatcher;
import com.libreshockwave.player.input.InputState;
import com.libreshockwave.player.render.pipeline.RenderSprite;
import com.libreshockwave.player.render.pipeline.StageRenderer;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputHandlerMouseClickTest {

    @Test
    void clickOnTracksFrontMostActiveSpriteWhileEventsTargetInteractiveSprite() {
        InputState inputState = new InputState();
        StageRenderer stageRenderer = new StageRenderer(null);
        stageRenderer.setLastBakedSprites(List.of(
                sprite(10, 10, 10),
                sprite(11, 10, 10)
        ));

        RecordingDispatcher dispatcher = new RecordingDispatcher(inputState, 10);
        InputHandler handler = new InputHandler(
                inputState,
                stageRenderer,
                new CastLibManager(null, null),
                () -> 1,
                () -> dispatcher);

        handler.onMouseDown(11, 11, false);
        handler.onMouseUp(11, 11, false);
        handler.processInputEvents();

        assertEquals(10, inputState.getClickOnSprite(),
                "the clickOn should ignore inactive visual sprites and report the active clicked sprite");
        assertEquals(10, dispatcher.clickOnDuringMouseUp);
        assertEquals(List.of("mouseDown:10", "mouseUp:10", "mouseEnter:10", "mouseWithin:10"),
                dispatcher.spriteEvents);
    }

    @Test
    void clickOnIgnoresInactiveFullscreenRefreshHelper() {
        InputState inputState = new InputState();
        StageRenderer stageRenderer = new StageRenderer(null);
        stageRenderer.setLastBakedSprites(List.of(
                sprite(1, -1, 0, 961, 540, RenderSprite.SpriteType.BITMAP)
        ));

        RecordingDispatcher dispatcher = new RecordingDispatcher(inputState, 0);
        InputHandler handler = new InputHandler(
                inputState,
                stageRenderer,
                new CastLibManager(null, null),
                () -> 1,
                () -> dispatcher);

        handler.onMouseDown(40, 40, false);
        handler.onMouseUp(40, 40, false);
        handler.processInputEvents();

        assertEquals(0, inputState.getClickOnSprite(),
                "inactive repaint/layout helper sprites should not replace the Stage clickOn value");
        assertEquals(0, dispatcher.clickOnDuringMouseUp);
        assertEquals(List.of(), dispatcher.spriteEvents);
    }

    @Test
    void clickOnDetectsButtonSpritesWithoutMouseScripts() {
        InputState inputState = new InputState();
        StageRenderer stageRenderer = new StageRenderer(null);
        stageRenderer.setLastBakedSprites(List.of(
                sprite(20, 10, 10, 24, 12, RenderSprite.SpriteType.BUTTON)
        ));

        RecordingDispatcher dispatcher = new RecordingDispatcher(inputState, 0);
        InputHandler handler = new InputHandler(
                inputState,
                stageRenderer,
                new CastLibManager(null, null),
                () -> 1,
                () -> dispatcher);

        handler.onMouseDown(11, 11, false);
        handler.onMouseUp(11, 11, false);
        handler.processInputEvents();

        assertEquals(20, inputState.getClickOnSprite(),
                "Director clickOn still detects button-like controls without attached mouse scripts");
        assertEquals(20, dispatcher.clickOnDuringMouseUp);
    }

    @Test
    void mousePressRefreshesRolloverAtClickLocation() {
        InputState inputState = new InputState();
        StageRenderer stageRenderer = new StageRenderer(null);
        stageRenderer.setLastBakedSprites(List.of(
                sprite(30, 10, 10)
        ));

        RecordingDispatcher dispatcher = new RecordingDispatcher(inputState, 30);
        InputHandler handler = new InputHandler(
                inputState,
                stageRenderer,
                new CastLibManager(null, null),
                () -> 1,
                () -> dispatcher);

        handler.onMouseDown(11, 11, false);

        assertEquals(30, inputState.getRolloverSprite(),
                "rollover() should not require a prior mouseMove before the click");
    }

    @Test
    void mouseEventsExposeEventTargetAsCurrentRolloverDuringDispatch() {
        InputState inputState = new InputState();
        StageRenderer stageRenderer = new StageRenderer(null);
        stageRenderer.setLastBakedSprites(List.of(
                sprite(35, 10, 10)
        ));

        RecordingDispatcher dispatcher = new RecordingDispatcher(inputState, 35);
        InputHandler handler = new InputHandler(
                inputState,
                stageRenderer,
                new CastLibManager(null, null),
                () -> 1,
                () -> dispatcher);

        handler.onMouseDown(11, 11, false);
        handler.onMouseUp(11, 11, false);
        handler.processInputEvents();

        assertEquals(35, dispatcher.rolloverDuringMouseDown,
                "mouseDown handlers should see Director rollover aligned with the event sprite");
        assertEquals(35, dispatcher.rolloverDuringMouseUp,
                "mouseUp handlers should see Director rollover aligned with the release sprite");
    }

    @Test
    void rolloverRecalculationSkipsHiddenFrontSprite() {
        InputState inputState = new InputState();
        StageRenderer stageRenderer = new StageRenderer(null);
        stageRenderer.setLastBakedSprites(List.of(
                sprite(60, 10, 10),
                sprite(61, 10, 10)
        ));
        stageRenderer.getSpriteRegistry().getOrCreateDynamic(60);
        stageRenderer.getSpriteRegistry().getOrCreateDynamic(61).setVisible(false);

        RecordingDispatcher dispatcher = new RecordingDispatcher(inputState, 60, 61);
        InputHandler handler = new InputHandler(
                inputState,
                stageRenderer,
                new CastLibManager(null, null),
                () -> 1,
                () -> dispatcher);

        inputState.setMousePosition(11, 11);

        assertEquals(60, handler.resolveRolloverAtCurrentMouse(),
                "when authored Lingo hides the current rollover sprite, Director hit testing should see the next visible sprite");
    }

    @Test
    void rolloverOnlyEventsInvalidateSpriteRevision() {
        InputState inputState = new InputState();
        StageRenderer stageRenderer = new StageRenderer(null);
        stageRenderer.setLastBakedSprites(List.of(
                sprite(40, 10, 10)
        ));

        RecordingDispatcher dispatcher = new RecordingDispatcher(inputState, 40);
        InputHandler handler = new InputHandler(
                inputState,
                stageRenderer,
                new CastLibManager(null, null),
                () -> 1,
                () -> dispatcher);

        handler.onMouseMove(11, 11);
        int revisionBefore = stageRenderer.getSpriteRegistry().getRevision();
        handler.processInputEvents();

        assertEquals(List.of("mouseEnter:40", "mouseWithin:40"), dispatcher.spriteEvents);
        assertTrue(stageRenderer.getSpriteRegistry().getRevision() > revisionBefore,
                "hover handlers can mutate visual state even when no mouse button event is queued");
    }

    @Test
    void rolloverEventsDoNotInheritStoppedMouseDownState() {
        InputState inputState = new InputState();
        StageRenderer stageRenderer = new StageRenderer(null);
        stageRenderer.setLastBakedSprites(List.of(
                sprite(50, 10, 10)
        ));

        RecordingDispatcher dispatcher = new RecordingDispatcher(inputState, 50);
        dispatcher.stopOnMouseDown = true;
        InputHandler handler = new InputHandler(
                inputState,
                stageRenderer,
                new CastLibManager(null, null),
                () -> 1,
                () -> dispatcher);

        handler.onMouseDown(11, 11, false);
        handler.processInputEvents();

        assertEquals(List.of("mouseDown:50", "mouseEnter:50", "mouseWithin:50"),
                dispatcher.spriteEvents);
        assertEquals(List.of(false, false, false), dispatcher.stoppedAtSpriteDispatch,
                "each Director mouse event should start with a fresh stopEvent state");
    }

    private static RenderSprite sprite(int channel, int x, int y) {
        return sprite(channel, x, y, 3, 3, RenderSprite.SpriteType.BITMAP);
    }

    private static RenderSprite sprite(int channel, int x, int y, int width, int height,
                                       RenderSprite.SpriteType type) {
        Bitmap bitmap = new Bitmap(3, 3, 32);
        bitmap.fill(0xFFFF0000);
        return new RenderSprite(
                channel,
                x, y,
                width, height,
                channel,
                true,
                type,
                null,
                null,
                0, 0,
                false, false,
                InkMode.COPY.code(), 100,
                false, false,
                bitmap,
                false);
    }

    private static final class RecordingDispatcher extends EventDispatcher {
        private final InputState inputState;
        private final List<Integer> interactiveChannels;
        private final List<String> spriteEvents = new ArrayList<>();
        private final List<Boolean> stoppedAtSpriteDispatch = new ArrayList<>();
        private int clickOnDuringMouseUp = -1;
        private int rolloverDuringMouseDown = -1;
        private int rolloverDuringMouseUp = -1;
        private boolean stopped;
        private boolean stopOnMouseDown;

        private RecordingDispatcher(InputState inputState, int... interactiveChannels) {
            super(null, new LingoVM(null), new BehaviorManager(null));
            this.inputState = inputState;
            this.interactiveChannels = new ArrayList<>();
            Arrays.stream(interactiveChannels).forEach(this.interactiveChannels::add);
        }

        @Override
        public boolean isSpriteMouseInteractive(int channel) {
            return interactiveChannels.contains(channel);
        }

        @Override
        public void dispatchSpriteEvent(int channel, String handlerName, List<Datum> args) {
            stoppedAtSpriteDispatch.add(stopped);
            spriteEvents.add(handlerName + ":" + channel);
            if (PlayerEvent.MOUSE_DOWN.getHandlerName().equals(handlerName)) {
                rolloverDuringMouseDown = inputState.getRolloverSprite();
            }
            if (PlayerEvent.MOUSE_UP.getHandlerName().equals(handlerName)) {
                rolloverDuringMouseUp = inputState.getRolloverSprite();
            }
            if (stopOnMouseDown && PlayerEvent.MOUSE_DOWN.getHandlerName().equals(handlerName)) {
                stopped = true;
            }
        }

        @Override
        public void dispatchFrameAndMovieEvent(PlayerEvent event, List<Datum> args) {
            if (event == PlayerEvent.MOUSE_UP) {
                clickOnDuringMouseUp = inputState.getClickOnSprite();
            }
        }

        @Override
        public boolean isEventStopped() {
            return stopped;
        }

        @Override
        public void resetEventStopped() {
            stopped = false;
        }
    }
}
