package com.libreshockwave.player.event;

import com.libreshockwave.player.behavior.BehaviorManager;
import com.libreshockwave.player.render.SpriteRegistry;
import com.libreshockwave.player.sprite.SpriteState;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EventDispatcherTest {

    @Test
    void procListWithoutAuthoredMouseHandlerIsNotDispatchedByJavaShortcut() {
        RecordingVM vm = new RecordingVM();
        EventDispatcher dispatcher = new EventDispatcher(null, vm, new BehaviorManager(null));
        dispatcher.setSpriteRegistry(spriteRegistryWithBroker("mouseDown"));

        assertFalse(dispatcher.spriteHasHandler(12, "mouseDown"));

        dispatcher.dispatchSpriteEvent(12, "mouseDown", List.of());

        assertEquals("", vm.lastHandlerName);
        assertEquals(List.of(), vm.lastArgs);
    }

    private static SpriteRegistry spriteRegistryWithBroker(String eventName) {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteState sprite = new SpriteState(12);
        Datum.ScriptInstance broker = new Datum.ScriptInstance(100, new LinkedHashMap<>());
        broker.properties().put("id", Datum.of("editable_field"));

        Datum.PropList procList = new Datum.PropList();
        procList.put(eventName, true, new Datum.List(List.of(
                Datum.symbol("eventProcBroker"),
                Datum.symbol("controller_object"))));
        broker.properties().put("pProcList", procList);

        sprite.setScriptInstanceList(List.of(broker));
        registry.getAll().put(12, sprite);
        return registry;
    }

    private static final class RecordingVM extends LingoVM {
        private String lastHandlerName = "";
        private List<Datum> lastArgs = List.of();

        private RecordingVM() {
            super(null);
        }

        @Override
        public Datum callHandler(String handlerName, List<Datum> args) {
            if ("call".equals(handlerName)) {
                lastHandlerName = handlerName;
                lastArgs = List.copyOf(args);
                return Datum.TRUE;
            }
            return Datum.VOID;
        }
    }
}
